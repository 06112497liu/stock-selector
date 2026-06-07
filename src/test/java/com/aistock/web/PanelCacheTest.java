package com.aistock.web;

import com.aistock.datasource.Bar;
import com.aistock.datasource.DataSource;
import com.aistock.service.MarketDataService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PanelCache} 单测:命中复用、TTL 失效、手动刷新失效、回测缓存只跑一次。
 * 用计数型 fake DataSource(统计 updateBars 调用次数)+ 可注入时钟,绝不触网。
 */
class PanelCacheTest {

    private static final List<String> CODES = List.of("C0", "C1");

    /** 计数型数据源:每次 updateBars 计数 +1,返回固定 10 天合成 bar。 */
    static final class CountingSource implements DataSource {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public List<Bar> updateBars(String code) {
            calls.incrementAndGet();
            List<Bar> out = new ArrayList<>();
            LocalDate d = LocalDate.of(2024, 1, 1);
            double c = 100.0;
            for (int i = 0; i < 10; i++) {
                out.add(new Bar(d, c, c, c, c, 1000L));
                c *= 1.01;
                d = d.plusDays(1);
            }
            return out;
        }
    }

    private static MarketDataService svc(CountingSource src) {
        return new MarketDataService(src, code -> "名称-" + code,
                code -> java.util.OptionalDouble.empty(), CODES);
    }

    @Test
    void secondCall_hitsCache_noNewFetch() {
        CountingSource us = new CountingSource();
        PanelCache cache = new PanelCache(svc(us), svc(new CountingSource()), null, 1800, () -> 0L);

        PanelCache.PanelBundle first = cache.bundle("us");
        int afterFirst = us.calls.get();
        assertEquals(CODES.size(), afterFirst, "首次应对每只票各拉一次");

        PanelCache.PanelBundle second = cache.bundle("us");
        assertEquals(afterFirst, us.calls.get(), "命中缓存:第二次不应再触发 updateBars");
        assertSame(first, second, "命中应复用同一个面板包");
        assertEquals(first.latestDay(), second.latestDay());
    }

    @Test
    void ttlExpiry_rebuilds_andRefetches() {
        CountingSource us = new CountingSource();
        AtomicLong now = new AtomicLong(0L);
        PanelCache cache = new PanelCache(svc(us), svc(new CountingSource()), null, 1800, now::get);

        cache.bundle("us");
        int afterFirst = us.calls.get();

        // 未过期:命中
        now.set(1800_000L - 1);
        cache.bundle("us");
        assertEquals(afterFirst, us.calls.get(), "TTL 内应命中");

        // 过期:重建
        now.set(1800_000L + 1);
        cache.bundle("us");
        assertEquals(afterFirst * 2, us.calls.get(), "过期后应重新构建并再次触发 source");
    }

    @Test
    void refresh_invalidates_forcesRebuild() {
        CountingSource us = new CountingSource();
        PanelCache cache = new PanelCache(svc(us), svc(new CountingSource()), null, 1800, () -> 0L);

        cache.bundle("us");
        int afterFirst = us.calls.get();

        cache.invalidate("us");
        cache.bundle("us");
        assertEquals(afterFirst * 2, us.calls.get(), "刷新后应重新构建(再次触发 source)");
    }

    @Test
    void backtest_cachedByMarket_buildsOnce() {
        CountingSource us = new CountingSource();
        PanelCache cache = new PanelCache(svc(us), svc(new CountingSource()), null, 1800, () -> 0L);

        AtomicInteger builderCalls = new AtomicInteger();
        SignalService.BacktestView v1 = cache.backtest("us", () -> {
            builderCalls.incrementAndGet();
            return SignalService.BacktestView.empty("us");
        });
        SignalService.BacktestView v2 = cache.backtest("us", () -> {
            builderCalls.incrementAndGet();
            return SignalService.BacktestView.empty("us");
        });

        assertEquals(1, builderCalls.get(), "回测结果应缓存:连续两次只跑一次 builder");
        assertSame(v1, v2, "命中应复用同一回测视图");
    }

    @Test
    void markets_areIsolated() {
        CountingSource us = new CountingSource();
        CountingSource cn = new CountingSource();
        PanelCache cache = new PanelCache(svc(us), svc(cn), null, 1800, () -> 0L);

        cache.bundle("us");
        assertEquals(CODES.size(), us.calls.get());
        assertEquals(0, cn.calls.get(), "拉 us 不应触发 cn");

        cache.bundle("cn");
        assertEquals(CODES.size(), cn.calls.get(), "cn 独立构建");
    }

    @Test
    void invalidate_alsoClearsBacktest() {
        CountingSource us = new CountingSource();
        PanelCache cache = new PanelCache(svc(us), svc(new CountingSource()), null, 1800, () -> 0L);

        AtomicInteger builderCalls = new AtomicInteger();
        cache.backtest("us", () -> {
            builderCalls.incrementAndGet();
            return SignalService.BacktestView.empty("us");
        });
        cache.invalidate("us");
        cache.backtest("us", () -> {
            builderCalls.incrementAndGet();
            return SignalService.BacktestView.empty("us");
        });
        assertTrue(builderCalls.get() == 2, "刷新应同时清回测缓存,重新构建");
    }
}
