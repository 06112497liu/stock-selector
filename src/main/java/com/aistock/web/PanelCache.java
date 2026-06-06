package com.aistock.web;

import com.aistock.feature.MarketPanel;
import com.aistock.service.MarketDataService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 按 market 缓存「行情面板包」(MarketPanel + names + 最新交易日)与回测结果,带 TTL。
 *
 * <p><b>动机</b>:每个 Web 请求若各自 {@link MarketDataService#buildPanel()},会对篮子里
 * 每只票串行触网拉增量(最新交易日 < 日历今天时几乎每次都重拉)+ 重算因子,/backtest 还
 * 每次重训 ML + 跑双回测,导致 1.6~2.6s 卡顿。对标 Python 原版 {@code st.cache_data(ttl=1800)}
 * 缓存整个 market panel,这里补上这层。
 *
 * <p><b>新鲜度</b>:面板默认 30 分钟过期(可配 {@code market.cache-ttl-seconds},默认 1800),
 * 命中即毫秒级返回;{@link #invalidate(String)}(手动刷新)立即清掉该 market 缓存,下次重建。
 *
 * <p><b>并发</b>:缓存查找用 {@link ConcurrentHashMap};未命中 / 过期时<b>对每 market 单独加锁</b>
 * (per-market lock),让首个线程构建、其余等待复用,避免并发首次重复触网。
 *
 * <p><b>边界</b>:只缓存只读不可变的 MarketPanel + names + day,以及回测视图;<b>绝不缓存</b>
 * 持仓 / 账本(那是 Store,业务层实时读)。时钟可注入,便于测 TTL。
 */
@Component
public class PanelCache {

    /** 默认 TTL:30 分钟(对标 Python st.cache_data(ttl=1800))。 */
    public static final long DEFAULT_TTL_SECONDS = 1800;

    private final MarketDataService us;
    private final MarketDataService cn;
    private final long ttlMillis;
    private final LongSupplier clock;

    /** 面板包缓存:market -> 带时间戳的包。 */
    private final Map<String, Stamped<PanelBundle>> panelCache = new ConcurrentHashMap<>();
    /** 回测视图缓存:market -> 带时间戳的视图。 */
    private final Map<String, Stamped<SignalService.BacktestView>> backtestCache = new ConcurrentHashMap<>();
    /** per-market 锁:避免并发首次重复构建。 */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public PanelCache(@Qualifier("usMarketDataService") MarketDataService usMarketDataService,
                      @Qualifier("cnMarketDataService") MarketDataService cnMarketDataService,
                      @Value("${market.cache-ttl-seconds:1800}") long ttlSeconds) {
        this(usMarketDataService, cnMarketDataService, ttlSeconds, System::currentTimeMillis);
    }

    /** 可注入时钟的构造(测试用)。 */
    public PanelCache(MarketDataService us, MarketDataService cn, long ttlSeconds, LongSupplier clock) {
        this.us = us;
        this.cn = cn;
        this.ttlMillis = ttlSeconds * 1000L;
        this.clock = clock;
    }

    private MarketDataService serviceFor(String market) {
        return "cn".equals(market) ? cn : us;
    }

    private Object lockFor(String market) {
        return locks.computeIfAbsent(market, k -> new Object());
    }

    /**
     * 取某 market 的面板包(命中且未过期则毫秒级返回,否则单线程构建并缓存)。
     *
     * @param market 已规整的 market("us" | "cn")
     */
    public PanelBundle bundle(String market) {
        market = SignalService.normalizeMarket(market);
        Stamped<PanelBundle> hit = panelCache.get(market);
        if (fresh(hit)) {
            return hit.value;
        }
        synchronized (lockFor(market)) {
            // 双检:可能已有别的线程在持锁期间构建好。
            hit = panelCache.get(market);
            if (fresh(hit)) {
                return hit.value;
            }
            MarketDataService svc = serviceFor(market);
            MarketPanel panel = svc.buildPanel();
            Map<String, String> names = svc.names();
            Map<String, OptionalDouble> marketCaps = svc.marketCaps();
            LocalDate day = svc.latestDay(panel);
            PanelBundle bundle = new PanelBundle(panel, names, marketCaps, day);
            panelCache.put(market, new Stamped<>(bundle, clock.getAsLong()));
            return bundle;
        }
    }

    /**
     * 取某 market 的回测视图(命中且未过期则复用,否则基于缓存面板算一次并缓存)。
     *
     * <p>builder 仅在未命中时调用一次(贵:ML 训练 + 双回测),同一 per-market 锁串行化。
     *
     * @param market  已规整的 market
     * @param builder 真正跑 BacktestComparison 的逻辑(未命中时调用)
     */
    public SignalService.BacktestView backtest(String market, java.util.function.Supplier<SignalService.BacktestView> builder) {
        market = SignalService.normalizeMarket(market);
        Stamped<SignalService.BacktestView> hit = backtestCache.get(market);
        if (fresh(hit)) {
            return hit.value;
        }
        synchronized (lockFor(market)) {
            hit = backtestCache.get(market);
            if (fresh(hit)) {
                return hit.value;
            }
            SignalService.BacktestView view = builder.get();
            backtestCache.put(market, new Stamped<>(view, clock.getAsLong()));
            return view;
        }
    }

    /**
     * 清掉某 market 的面板 + 回测缓存(手动「刷新最新数据」)。下次访问重新触网构建。
     */
    public void invalidate(String market) {
        market = SignalService.normalizeMarket(market);
        synchronized (lockFor(market)) {
            panelCache.remove(market);
            backtestCache.remove(market);
        }
    }

    /**
     * 只清掉某 market 的<b>回测缓存</b>(面板保留)。供策略参数变更后调用:
     * 参数只影响选股 / 回测结果,不影响行情面板,故无需重新触网拉面板,代价更小。
     */
    public void invalidateBacktest(String market) {
        market = SignalService.normalizeMarket(market);
        synchronized (lockFor(market)) {
            backtestCache.remove(market);
        }
    }

    private boolean fresh(Stamped<?> s) {
        return s != null && (clock.getAsLong() - s.stampedAt) < ttlMillis;
    }

    /** 行情面板包:只读不可变的 MarketPanel + names + 市值 + 最新交易日。 */
    public record PanelBundle(MarketPanel panel,
                              Map<String, String> names,
                              Map<String, OptionalDouble> marketCaps,
                              LocalDate latestDay) {
    }

    /** 带构建时间戳的缓存条目。 */
    private static final class Stamped<T> {
        final T value;
        final long stampedAt;

        Stamped(T value, long stampedAt) {
            this.value = value;
            this.stampedAt = stampedAt;
        }
    }
}
