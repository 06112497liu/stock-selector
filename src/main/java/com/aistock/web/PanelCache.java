package com.aistock.web;

import com.aistock.feature.MarketPanel;
import com.aistock.service.MarketDataService;
import com.aistock.storage.WatchlistStore;
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
 * <p>支持三种 market key:
 * <ul>
 *   <li>{@code "us"} — 内置美股篮子</li>
 *   <li>{@code "cn"} — 内置 A 股篮子</li>
 *   <li>{@code "wl_xxx"} — 自选股分组(由 {@link WatchlistService} 动态提供
 *       {@link MarketDataService},每个分组独立缓存)</li>
 * </ul>
 */
@Component
public class PanelCache {

    public static final long DEFAULT_TTL_SECONDS = 1800;

    private final MarketDataService us;
    private final MarketDataService cn;
    private final WatchlistService watchlistService;
    private final long ttlMillis;
    private final LongSupplier clock;

    private final Map<String, Stamped<PanelBundle>> panelCache = new ConcurrentHashMap<>();
    private final Map<String, Stamped<SignalService.BacktestView>> backtestCache = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public PanelCache(@Qualifier("usMarketDataService") MarketDataService usMarketDataService,
                      @Qualifier("cnMarketDataService") MarketDataService cnMarketDataService,
                      WatchlistService watchlistService,
                      @Value("${market.cache-ttl-seconds:1800}") long ttlSeconds) {
        this(usMarketDataService, cnMarketDataService, watchlistService, ttlSeconds, System::currentTimeMillis);
    }

    public PanelCache(MarketDataService us, MarketDataService cn, WatchlistService watchlistService,
                      long ttlSeconds, LongSupplier clock) {
        this.us = us;
        this.cn = cn;
        this.watchlistService = watchlistService;
        this.ttlMillis = ttlSeconds * 1000L;
        this.clock = clock;
    }

    private MarketDataService serviceFor(String market) {
        if ("cn".equals(market)) return cn;
        if ("us".equals(market)) return us;
        if (WatchlistStore.isWatchlist(market)) {
            return watchlistService.marketDataServiceFor(market);
        }
        return us;
    }

    private Object lockFor(String market) {
        return locks.computeIfAbsent(market, k -> new Object());
    }

    public PanelBundle bundle(String market) {
        market = SignalService.normalizeMarket(market);
        Stamped<PanelBundle> hit = panelCache.get(market);
        if (fresh(hit)) {
            return hit.value;
        }
        synchronized (lockFor(market)) {
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

    public void invalidate(String market) {
        market = SignalService.normalizeMarket(market);
        synchronized (lockFor(market)) {
            panelCache.remove(market);
            backtestCache.remove(market);
        }
    }

    public void invalidateBacktest(String market) {
        market = SignalService.normalizeMarket(market);
        synchronized (lockFor(market)) {
            backtestCache.remove(market);
        }
    }

    private boolean fresh(Stamped<?> s) {
        return s != null && (clock.getAsLong() - s.stampedAt) < ttlMillis;
    }

    public record PanelBundle(MarketPanel panel,
                              Map<String, String> names,
                              Map<String, OptionalDouble> marketCaps,
                              LocalDate latestDay) {
    }

    private static final class Stamped<T> {
        final T value;
        final long stampedAt;

        Stamped(T value, long stampedAt) {
            this.value = value;
            this.stampedAt = stampedAt;
        }
    }
}
