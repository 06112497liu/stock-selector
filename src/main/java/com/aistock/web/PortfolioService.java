package com.aistock.web;

import com.aistock.feature.MarketPanel;
import com.aistock.service.MarketDataService;
import com.aistock.storage.PortfolioView;
import com.aistock.storage.Reconcile;
import com.aistock.storage.Store;
import com.aistock.storage.Store.NavPoint;
import com.aistock.storage.Store.Position;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 账本 / 净值视图门面:按 market 选对应 {@link Store} 与 {@link MarketDataService},
 * 组装「持仓与净值」页与「对账」页的视图,并落净值。
 *
 * <p>边界铁律:本服务<b>不参与选股 / 不按资产算金额</b>。账本只用于对账、净值展示,
 * 以及给 /signals 提供「持有哪些 code + 入场价(avg_cost)」。
 */
@Service
public class PortfolioService {

    private final MarketDataService us;
    private final MarketDataService cn;
    private final Store usStore;
    private final Store cnStore;
    private final PanelCache panelCache;

    public PortfolioService(@Qualifier("usMarketDataService") MarketDataService us,
                            @Qualifier("cnMarketDataService") MarketDataService cn,
                            @Qualifier("usStore") Store usStore,
                            @Qualifier("cnStore") Store cnStore,
                            PanelCache panelCache) {
        this.us = us;
        this.cn = cn;
        this.usStore = usStore;
        this.cnStore = cnStore;
        this.panelCache = panelCache;
    }

    /** 按 market 选账本(供其它服务复用,如 SignalService 读真实持仓)。 */
    public Store storeFor(String market) {
        return "cn".equals(SignalService.normalizeMarket(market)) ? cnStore : usStore;
    }

    private MarketDataService serviceFor(String market) {
        return "cn".equals(SignalService.normalizeMarket(market)) ? cn : us;
    }

    /**
     * 组装「持仓与净值」页视图。
     *
     * <p><b>副作用</b>:访问时按当前持仓 + 最新交易日现价算净值,并 {@code recordNav(当天)}
     * 一笔,让净值曲线随访问自然累积。无最新交易日(完全无行情)时不落净值。
     */
    public PortfolioPageView portfolio(String market) {
        market = SignalService.normalizeMarket(market);
        Store store = storeFor(market);
        MarketDataService svc = serviceFor(market);

        Map<String, Position> positions = store.getPositions();
        double cash = store.getCash();

        // 行情面板走缓存(命中即毫秒级,不再每次触网);现价由 service 对缓存面板纯计算得出。
        PanelCache.PanelBundle bundle = panelCache.bundle(market);
        Map<String, String> names = bundle.names();
        MarketPanel panel = bundle.panel();
        LocalDate day = bundle.latestDay();
        Map<String, Double> latestClose = svc.latestCloseByCode(panel);

        List<PortfolioView.Row> rows = PortfolioView.positionView(positions, names, latestClose);
        PortfolioView.Summary summary = PortfolioView.accountSummary(cash, positions, latestClose);

        // 访问即记录当日净值(同一交易日 upsert,不重复堆叠)。
        if (day != null) {
            store.recordNav(day.toString(), summary.netValue());
        }

        List<NavPoint> nav = store.navHistory();
        return new PortfolioPageView(market, rows, summary, day, nav);
    }

    /**
     * 组装「对账」页视图(把当前账本回显成可编辑表单的初值)。
     */
    public ReconcilePageView reconcileForm(String market) {
        market = SignalService.normalizeMarket(market);
        Store store = storeFor(market);
        MarketDataService svc = serviceFor(market);
        Map<String, Position> positions = store.getPositions();
        double cash = store.getCash();
        Map<String, String> names = svc.names();
        return new ReconcilePageView(market, positions, cash, names);
    }

    /**
     * 应用对账:整体替换持仓 + 设现金 + 清空净值历史(重置基准)。
     *
     * @param positions 真实持仓(已解析好;为空则清空持仓)
     * @param cash      真实现金
     */
    public void applyReconcile(String market, Map<String, Position> positions, double cash) {
        market = SignalService.normalizeMarket(market);
        Reconcile.applyReconciliation(storeFor(market), positions, cash);
    }

    /** 「持仓与净值」页视图模型(只读)。 */
    public record PortfolioPageView(String market,
                                    List<PortfolioView.Row> rows,
                                    PortfolioView.Summary summary,
                                    LocalDate latestDay,
                                    List<NavPoint> navHistory) {
        public boolean hasNav() {
            return navHistory != null && !navHistory.isEmpty();
        }

        /**
         * 净值折线 points(视口 640x240,留边距);无点或仅一点也能渲染。
         * 空净值返回空串(模板据此显示「暂无净值记录,先去对账」)。
         */
        public String navPolyline() {
            if (!hasNav()) {
                return "";
            }
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (NavPoint p : navHistory) {
                min = Math.min(min, p.nav());
                max = Math.max(max, p.nav());
            }
            double xLeft = 20, xRight = 620, yTop = 20, yBottom = 220;
            int n = navHistory.size();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                double x = n > 1 ? xLeft + (double) i / (n - 1) * (xRight - xLeft) : xLeft;
                double y = max > min
                        ? yBottom - (navHistory.get(i).nav() - min) / (max - min) * (yBottom - yTop)
                        : (yTop + yBottom) / 2.0;
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(String.format("%.1f,%.1f", x, y));
            }
            return sb.toString();
        }
    }

    /** 「对账」页视图模型(回显当前账本为表单初值)。 */
    public record ReconcilePageView(String market,
                                    Map<String, Position> positions,
                                    double cash,
                                    Map<String, String> names) {
        public String nameOf(String code) {
            String n = names == null ? null : names.get(code);
            return n == null ? code : n;
        }
    }
}
