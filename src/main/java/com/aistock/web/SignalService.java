package com.aistock.web;

import com.aistock.backtest.BacktestComparison;
import com.aistock.backtest.BacktestEngine;
import com.aistock.backtest.BacktestResult;
import com.aistock.backtest.CostConfig;
import com.aistock.feature.MarketPanel;
import com.aistock.notify.ServerChanNotifier;
import com.aistock.notify.SignalFormatter;
import com.aistock.recommend.RecommendEngine;
import com.aistock.recommend.Recommendation;
import com.aistock.selector.FactorSelector;
import com.aistock.service.MarketDataService;
import com.aistock.storage.ParamsStore;
import com.aistock.storage.StrategyParams;
import com.aistock.storage.Store;
import com.aistock.storage.Store.Position;
import com.aistock.storage.WatchlistStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Web 层与领域引擎之间的薄门面:按 market 选择对应 {@link MarketDataService},
 * 串起 buildPanel → recommend / backtest,并统一处理「无数据」降级。
 *
 * <p>不持有状态,不下单、不记账;只把纯名单 / 回测指标交给视图渲染。
 */
@Service
public class SignalService {

    /** 默认买入名单取前 N。 */
    public static final int DEFAULT_TOP_N = 5;
    /** 默认个股止损线(自入场价跌 8%)。 */
    public static final double DEFAULT_STOP_LOSS_PCT = -0.08;
    /** 样本外切分比例:前 70% 交易日为样本内,其后为样本外回测区间。 */
    public static final double OUT_OF_SAMPLE_SPLIT = 0.70;
    /** 数据最新交易日距今超过该天数,提示可能未更新到最新。 */
    public static final long STALE_DAYS = 5;
    /** ML 标签前瞻交易日数 H(同时是防前视 embargo 长度下限)。 */
    public static final int ML_HORIZON = 5;

    private final Store usStore;
    private final Store cnStore;
    private final ParamsStore usParams;
    private final ParamsStore cnParams;
    private final ServerChanNotifier notifier;
    private final PanelCache panelCache;
    private final WatchlistService watchlistService;
    private final RecommendEngine recommendEngine = new RecommendEngine();
    private final BacktestEngine backtestEngine = new BacktestEngine();
    private final BacktestComparison comparison = new BacktestComparison();

    public SignalService(@Qualifier("usStore") Store usStore,
                         @Qualifier("cnStore") Store cnStore,
                         @Qualifier("usParams") ParamsStore usParams,
                         @Qualifier("cnParams") ParamsStore cnParams,
                         ServerChanNotifier notifier,
                         PanelCache panelCache,
                         WatchlistService watchlistService) {
        this.usStore = usStore;
        this.cnStore = cnStore;
        this.usParams = usParams;
        this.cnParams = cnParams;
        this.notifier = notifier;
        this.panelCache = panelCache;
        this.watchlistService = watchlistService;
    }

    private Store storeFor(String market) {
        market = normalizeMarket(market);
        if (WatchlistStore.isWatchlist(market)) {
            return watchlistService.storeFor(market);
        }
        return "cn".equals(market) ? cnStore : usStore;
    }

    private ParamsStore paramsStoreFor(String market) {
        market = normalizeMarket(market);
        if (WatchlistStore.isWatchlist(market)) {
            return watchlistService.paramsFor(market);
        }
        return "cn".equals(market) ? cnParams : usParams;
    }

    private String underlyingMarket(String market) {
        if (watchlistService == null) {
            return normalizeMarket(market);
        }
        return watchlistService.underlyingMarket(market);
    }

    /** 规整 market 参数:wl_ 前缀的自选股分组原样保留;其余非法值回退到 us。 */
    public static String normalizeMarket(String market) {
        if (market != null && WatchlistStore.isWatchlist(market)) {
            return market;
        }
        return "cn".equalsIgnoreCase(market) ? "cn" : "us";
    }


    /**
     * 生成某市场今日选股建议。
     *
     * <p><b>持仓来源口径</b>:默认从账本({@link Store})读真实持仓
     * (holdings = positions.keySet(),entryPrice = code → avg_cost),据此算「继续持有 / 该卖」;
     * 止损用对账里的成本价 avg_cost 当入场价。页面手动输入 holdings 时作为<b>补充试算</b>
     * 并入账本持仓(union,不抹掉账本持仓的「已持有」标注);入场价手输项覆盖账本 avg_cost。
     * 买入名单照旧只取选股器 topN,不按资产算金额。
     *
     * @param market      "us" | "cn"
     * @param holdingsCsv 持有 code,逗号分隔(可空 -> 用账本持仓)
     * @param entryPrice  已持有票入场价(可空 -> 回退账本 avg_cost)
     */
    public SignalView signals(String market, String holdingsCsv, Map<String, Double> entryPrice) {
        market = normalizeMarket(market);
        PanelCache.PanelBundle bundle = panelCache.bundle(market);
        MarketPanel panel = bundle.panel();
        Map<String, String> names = bundle.names();
        Map<String, java.util.OptionalDouble> marketCaps = bundle.marketCaps();
        LocalDate day = bundle.latestDay();

        if (day == null) {
            // 完全无数据(联网失败且无缓存):友好降级,不抛 500。
            String underlying = underlyingMarket(market);
            return SignalView.empty(market, underlying, names, marketCaps);
        }

        Map<String, Position> ledger = storeFor(market).getPositions();

        // 持仓:账本真实持仓为基底,手输为「补充试算」并入 —— 手输绝不抹掉
        // 账本持仓,否则「该买」名单里账本持仓的「已持有」标注会无故消失。
        Set<String> manual = parseCsv(holdingsCsv);
        Set<String> holdings = new LinkedHashSet<>(ledger.keySet());
        holdings.addAll(manual);

        // 入场价:账本 avg_cost 作基底,手输项覆盖(止损用 avg_cost,符合用户口径)。
        Map<String, Double> entries = new HashMap<>();
        for (Map.Entry<String, Position> e : ledger.entrySet()) {
            entries.put(e.getKey(), e.getValue().avgCost());
        }
        if (entryPrice != null) {
            entries.putAll(entryPrice);
        }

        StrategyParams params = paramsStoreFor(market).load();
        Recommendation reco = recommendEngine.recommend(
                panel, day, new FactorSelector(params.factorWeights()),
                params.topN(), holdings, entries, params.stopLossPct());

        boolean stale = day.isBefore(LocalDate.now().minusDays(STALE_DAYS));
        String underlying = underlyingMarket(market);
        return new SignalView(market, underlying, names, marketCaps, panel, day, stale, reco, holdingsCsv);
    }

    /**
     * 把某市场当日选股建议推送到微信(Server酱)。
     *
     * <p>持仓口径固定为「空持仓 -> 纯买入名单」(与 /signals 不带 holdings 时一致),
     * 推送只发名单、不下单。返回结果用于页面回显。
     *
     * @param market "us" | "cn"
     */
    public PushResult push(String market) {
        market = normalizeMarket(market);

        if (!notifier.isConfigured()) {
            return PushResult.NOT_CONFIGURED;
        }

        PanelCache.PanelBundle bundle = panelCache.bundle(market);
        MarketPanel panel = bundle.panel();
        Map<String, String> names = bundle.names();
        LocalDate day = bundle.latestDay();
        if (day == null) {
            return PushResult.NO_DATA;
        }

        StrategyParams params = paramsStoreFor(market).load();
        Recommendation reco = recommendEngine.recommend(
                panel, day, new FactorSelector(params.factorWeights()),
                params.topN(), Set.of(), Map.of(), params.stopLossPct());

        String title = SignalFormatter.pushTitle(day, reco);
        String body = SignalFormatter.toMarkdown(day, reco, names);
        boolean ok = notifier.send(title, body);
        return ok ? PushResult.SUCCESS : PushResult.FAILED;
    }

    /** 推送结果(供页面回显文案)。 */
    public enum PushResult {
        SUCCESS("已推送到微信"),
        FAILED("推送失败,请检查 SendKey 或网络"),
        NOT_CONFIGURED("未配置 Server酱 SendKey"),
        NO_DATA("暂无数据,无法推送");

        private final String message;

        PushResult(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    /**
     * 样本外回测:取面板交易日,在 70% 处切样本外起点,Factor 与 ML 两套策略并排回测对比。
     *
     * <p>ML 在 split 点用 split 之前数据训练一次(留 H 个交易日 embargo),与 Factor 同起点、
     * 同成本回测。两者 alpha 均未验证,仅作框架演示。
     */
    public BacktestView backtest(String market) {
        final String mkt = normalizeMarket(market);
        final StrategyParams params = paramsStoreFor(mkt).load();
        // 回测结果按 market + TTL 缓存:命中即复用,避免每次重训 ML + 跑双回测。
        return panelCache.backtest(mkt, () -> {
            MarketPanel panel = panelCache.bundle(mkt).panel();
            List<LocalDate> days = panel.tradingDays();
            if (days.isEmpty()) {
                return BacktestView.empty(mkt);
            }

            int idx = (int) Math.floor(days.size() * OUT_OF_SAMPLE_SPLIT);
            if (idx >= days.size()) {
                idx = days.size() - 1;
            }
            LocalDate split = days.get(idx);

            // 美股可传零印花税;成本口径从简(佣金+滑点)。
            // 自选股分组按其配置的底层 market_type(us/cn)决定印花税。
            String underlying = underlyingMarket(mkt);
            CostConfig cost = new CostConfig(0.0003, "cn".equals(underlying) ? 0.001 : 0.0, 0.0005);
            BacktestComparison.Result cmp = comparison.run(
                    panel, split, params.topN(), cost, ML_HORIZON, params.factorWeights());

            return new BacktestView(mkt, split, cmp.factor(), cmp.ml(),
                    cmp.mlTrained(), cmp.mlTrainSamples());
        });
    }

    /** 读取某 market 当前策略参数(无记录回默认)。 */
    public StrategyParams loadParams(String market) {
        return paramsStoreFor(market).load();
    }

    /**
     * 保存某 market 策略参数,并<b>清掉该 market 的回测缓存</b>。
     *
     * <p>清缓存口径:参数只影响选股 / 回测,不影响行情面板,故只清回测缓存
     * (面板保留,无需重新触网),下次 /backtest 会用新参数重算。
     * signals/push 每次实时 load 参数,无缓存,保存后即时生效。
     */
    public void saveParams(String market, StrategyParams params) {
        String mkt = normalizeMarket(market);
        paramsStoreFor(mkt).save(params);
        panelCache.invalidateBacktest(mkt);
    }

    /** 重置某 market 策略参数回默认,并清回测缓存。 */
    public void resetParams(String market) {
        String mkt = normalizeMarket(market);
        paramsStoreFor(mkt).reset();
        panelCache.invalidateBacktest(mkt);
    }

    private static Set<String> parseCsv(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null) {
            return out;
        }
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t.toUpperCase(Locale.ROOT)); // 统一大写,避免 aapl 与 AAPL 不匹配
            }
        }
        return out;
    }

    /** 解析「code:price」形式的入场价输入(逗号分隔,价格可选)。 */
    public static Map<String, Double> parseEntryPrice(String csv) {
        Map<String, Double> out = new HashMap<>();
        if (csv == null) {
            return out;
        }
        for (String pair : csv.split(",")) {
            String t = pair.trim();
            if (t.isEmpty()) {
                continue;
            }
            int colon = t.indexOf(':');
            if (colon <= 0 || colon == t.length() - 1) {
                continue;
            }
            String code = t.substring(0, colon).trim().toUpperCase(Locale.ROOT);
            try {
                double px = Double.parseDouble(t.substring(colon + 1).trim());
                if (!code.isEmpty() && px > 0) {
                    out.put(code, px);
                }
            } catch (NumberFormatException ignore) {
                // 忽略非法价格项
            }
        }
        return out;
    }

    /** 选股建议视图模型(只读)。 */
    public record SignalView(String market,
                             String underlyingMarket,
                             Map<String, String> names,
                             Map<String, java.util.OptionalDouble> marketCaps,
                             MarketPanel panel,
                             LocalDate latestDay,
                             boolean stale,
                             Recommendation reco,
                             String holdingsCsv) {
        public boolean hasData() {
            return latestDay != null;
        }

        public static SignalView empty(String market, String underlyingMarket, Map<String, String> names) {
            return empty(market, underlyingMarket, names, Map.of());
        }

        public static SignalView empty(String market, String underlyingMarket, Map<String, String> names,
                                       Map<String, java.util.OptionalDouble> marketCaps) {
            return new SignalView(market, underlyingMarket, names, marketCaps, null, null, false,
                    new Recommendation(List.of(), List.of(), List.of()), "");
        }

        /**
         * 该 code 当日涨跌幅比率 = (最新交易日收盘 − 前一交易日收盘) / 前一交易日收盘。
         *
         * <p>口径:最新交易日取 {@code panel.tradingDays()} 末位,前一交易日取倒数第二位;
         * 任一收盘价为 NaN、前一日收盘为 0、或交易日不足两天 → 返回 {@link Double#NaN}
         * (算不出,绝不报错)。
         */
        private double changeRatio(String code) {
            if (panel == null) {
                return Double.NaN;
            }
            List<LocalDate> days = panel.tradingDays();
            if (days.size() < 2) {
                return Double.NaN;
            }
            LocalDate last = days.get(days.size() - 1);
            LocalDate prev = days.get(days.size() - 2);
            double lastClose = panel.closeOn(code, last);
            double prevClose = panel.closeOn(code, prev);
            if (Double.isNaN(lastClose) || Double.isNaN(prevClose) || prevClose == 0.0) {
                return Double.NaN;
            }
            return (lastClose - prevClose) / prevClose;
        }

        /**
         * 该 code 当日涨跌幅显示串(模板直接用)。带符号、2 位小数百分比;
         * 算不出(无前一日收盘 / 停牌 / 数据不足)→ "N/A"。
         *
         * @return 形如 {@code +2.34%} / {@code -1.05%} / {@code 0.00%} / {@code N/A}
         */
        public String changeDisplay(String code) {
            double r = changeRatio(code);
            if (Double.isNaN(r)) {
                return "N/A";
            }
            return String.format(Locale.ROOT, "%+.2f%%", r * 100.0);
        }

        /**
         * 涨跌幅着色 class(沿用 app.css 的 A 股口径:涨=红 .gain、跌=绿 .loss)。
         * 涨 → "gain";跌 → "loss";持平(0)或算不出(N/A)→ ""(不着色)。
         */
        public String changeClass(String code) {
            double r = changeRatio(code);
            if (Double.isNaN(r) || r == 0.0) {
                return "";
            }
            return r > 0 ? "gain" : "loss";
        }

        /** 名称映射,缺失回退 code。 */
        public String nameOf(String code) {
            String n = names == null ? null : names.get(code);
            return n == null ? code : n;
        }

        /**
         * 该 code 的市值显示串(模板直接用)。拿不到 → "N/A"(绝不报错)。
         *
         * <p>量级缩写:≥1e12 万亿、≥1e8 亿(各保留 2 位),否则原值保留 0 位;
         * 币种前缀按 market:美股 {@code $}、A 股 {@code ¥}。</p>
         *
         * @param code 标的代码
         * @return 形如 {@code $4.57万亿} / {@code ¥15.00亿} / {@code N/A}
         */
        public String marketCapDisplay(String code) {
            java.util.OptionalDouble cap = marketCaps == null ? null : marketCaps.get(code);
            if (cap == null || cap.isEmpty()) {
                return "N/A";
            }
            double v = cap.getAsDouble();
            String currency = "cn".equals(underlyingMarket) ? "¥" : "$";
            if (v >= 1e12) {
                return currency + String.format(Locale.ROOT, "%.2f", v / 1e12) + "万亿";
            }
            if (v >= 1e8) {
                return currency + String.format(Locale.ROOT, "%.2f", v / 1e8) + "亿";
            }
            return currency + String.format(Locale.ROOT, "%.0f", v);
        }
    }

    /** 回测视图模型(只读)。 */
    public record BacktestView(String market,
                               LocalDate splitStart,
                               BacktestResult factorResult,
                               BacktestResult mlResult,
                               boolean mlTrained,
                               int mlTrainSamples) {
        public boolean hasData() {
            return factorResult != null;
        }

        public static BacktestView empty(String market) {
            return new BacktestView(market, null, null, null, false, 0);
        }

        /** Factor 末净值;无数据为 NaN。 */
        public double factorNavEnd() {
            return navEndOf(factorResult);
        }

        /** ML 末净值;未训练或无数据为 NaN。 */
        public double mlNavEnd() {
            return navEndOf(mlResult);
        }

        private static double navEndOf(BacktestResult r) {
            if (r == null) {
                return Double.NaN;
            }
            double[] nav = r.nav();
            return nav.length == 0 ? Double.NaN : nav[nav.length - 1];
        }

        /**
         * Factor 净值折线 points(两条曲线共用同一 y 量程,可直接叠加比较)。
         */
        public String factorPolyline() {
            return polyline(factorResult, sharedMin(), sharedMax());
        }

        /** ML 净值折线 points(与 Factor 共用同一 y 量程)。未训练时为空。 */
        public String mlPolyline() {
            return polyline(mlResult, sharedMin(), sharedMax());
        }

        private double sharedMin() {
            return extreme(true);
        }

        private double sharedMax() {
            return extreme(false);
        }

        /** 跨两条曲线取全局 min/max,保证同坐标系对比。 */
        private double extreme(boolean wantMin) {
            double acc = wantMin ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
            boolean any = false;
            for (BacktestResult r : new BacktestResult[]{factorResult, mlResult}) {
                if (r == null) {
                    continue;
                }
                for (double v : r.nav()) {
                    acc = wantMin ? Math.min(acc, v) : Math.max(acc, v);
                    any = true;
                }
            }
            return any ? acc : (wantMin ? 0.0 : 1.0);
        }

        /**
         * 预计算内联 SVG 折线 points(视口 640x240,留边距),
         * 用给定 [min,max] 量程映射;result 为空或无点时返回空串。
         */
        private static String polyline(BacktestResult r, double min, double max) {
            if (r == null) {
                return "";
            }
            double[] nav = r.nav();
            if (nav.length == 0) {
                return "";
            }
            double xLeft = 20, xRight = 620, yTop = 20, yBottom = 220;
            int n = nav.length;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                double x = n > 1 ? xLeft + (double) i / (n - 1) * (xRight - xLeft) : xLeft;
                double y = max > min
                        ? yBottom - (nav[i] - min) / (max - min) * (yBottom - yTop)
                        : (yTop + yBottom) / 2.0;
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(String.format("%.1f,%.1f", x, y));
            }
            return sb.toString();
        }
    }
}
