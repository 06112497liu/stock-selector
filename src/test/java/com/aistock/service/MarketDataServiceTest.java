package com.aistock.service;

import com.aistock.datasource.Bar;
import com.aistock.datasource.DataSource;
import com.aistock.feature.MarketPanel;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MarketDataService} using a fake {@link DataSource} (preset
 * bars per code) and a fake name resolver. No network, no cache, no panel
 * internals beyond its public surface.
 */
class MarketDataServiceTest {

    /** Fake DataSource returning preset bars per code (or empty for unknowns). */
    static class FakeSource implements DataSource {
        final Map<String, List<Bar>> byCode = new LinkedHashMap<>();
        // 线程安全收集:buildPanel 并发调用 updateBars。
        final List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public List<Bar> updateBars(String code) {
            calls.add(code);
            return byCode.getOrDefault(code, List.of());
        }
    }

    /** Builds enough daily bars (sequential dates, rising close) to clear the
     *  20-day factor windows so the codes actually enter the panel. */
    private static List<Bar> series(int n, double base) {
        List<Bar> bars = new ArrayList<>(n);
        LocalDate d = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < n; i++) {
            double c = base + i; // strictly increasing close
            bars.add(new Bar(d.plusDays(i), c, c + 1, c - 1, c, 1000 + i));
        }
        return bars;
    }

    @Test
    void buildPanelIncludesOnlyCodesWithData() {
        FakeSource source = new FakeSource();
        source.byCode.put("AAA", series(30, 10));
        source.byCode.put("BBB", series(30, 20));
        // "CCC" intentionally has no data -> source returns empty.

        MarketDataService svc = new MarketDataService(
                source, code -> code, code -> OptionalDouble.empty(), List.of("AAA", "BBB", "CCC"));

        MarketPanel panel = svc.buildPanel();

        // All three codes were queried (order not guaranteed under concurrency).
        assertEquals(3, source.calls.size());
        assertTrue(source.calls.containsAll(List.of("AAA", "BBB", "CCC")));
        // Panel has trading days from the two data-bearing codes.
        assertFalse(panel.tradingDays().isEmpty());

        LocalDate last = svc.latestDay(panel);
        // CCC has no bar on any day -> never a close.
        assertTrue(Double.isNaN(panel.closeOn("CCC", last)));
        // AAA/BBB do have closes on the last day.
        assertFalse(Double.isNaN(panel.closeOn("AAA", last)));
        assertFalse(Double.isNaN(panel.closeOn("BBB", last)));
    }

    @Test
    void namesCoverAllCodesAndDegradeFailuresToCode() {
        FakeSource source = new FakeSource();
        // Resolver knows AAA, returns null for BBB (simulating a misbehaving
        // resolver) and the code itself for CCC.
        Function<String, String> resolver = code -> switch (code) {
            case "AAA" -> "Apple-ish Inc.";
            case "BBB" -> null;          // service must backstop null -> code
            default -> code;             // CCC degrades to itself
        };

        MarketDataService svc = new MarketDataService(
                source, resolver, code -> OptionalDouble.empty(), List.of("AAA", "BBB", "CCC"));

        Map<String, String> names = svc.names();

        assertEquals(3, names.size());
        assertEquals("Apple-ish Inc.", names.get("AAA"));
        assertEquals("BBB", names.get("BBB")); // null backstopped to code
        assertEquals("CCC", names.get("CCC"));
    }

    @Test
    void namesAreCachedAcrossCalls() {
        FakeSource source = new FakeSource();
        int[] hits = {0};
        Function<String, String> resolver = code -> {
            hits[0]++;
            return "N-" + code;
        };

        MarketDataService svc = new MarketDataService(
                source, resolver, code -> OptionalDouble.empty(), List.of("AAA", "BBB"));

        svc.names();
        svc.names();

        // Resolver invoked exactly once per code despite two names() calls.
        assertEquals(2, hits[0]);
    }

    @Test
    void marketCapResolvedAndCachedAcrossCalls() {
        FakeSource source = new FakeSource();
        int[] hits = {0};
        Function<String, OptionalDouble> capFn = code -> {
            hits[0]++;
            return OptionalDouble.of(1.23e12);
        };

        MarketDataService svc = new MarketDataService(
                source, c -> c, capFn, List.of("AAA", "BBB"));

        // 多次调用,每 code 仅触发一次解析(像 names 那样缓存)。
        assertEquals(1.23e12, svc.marketCapOf("AAA").getAsDouble(), 1e-3);
        assertEquals(1.23e12, svc.marketCapOf("AAA").getAsDouble(), 1e-3);
        svc.marketCaps();
        svc.marketCaps();
        assertEquals(2, hits[0], "每 code 仅解析一次(AAA+BBB=2)");
    }

    @Test
    void marketCapMissReturnsEmptyAndCachesEmpty() {
        FakeSource source = new FakeSource();
        int[] hits = {0};
        Function<String, OptionalDouble> capFn = code -> {
            hits[0]++;
            return OptionalDouble.empty(); // 降级
        };

        MarketDataService svc = new MarketDataService(
                source, c -> c, capFn, List.of("AAA"));

        assertTrue(svc.marketCapOf("AAA").isEmpty());
        assertTrue(svc.marketCapOf("AAA").isEmpty());
        // empty 也缓存:仅解析一次。
        assertEquals(1, hits[0]);
    }

    @Test
    void marketCapResolverThrowDegradesToEmpty() {
        FakeSource source = new FakeSource();
        Function<String, OptionalDouble> capFn = code -> {
            throw new RuntimeException("boom");
        };
        MarketDataService svc = new MarketDataService(
                source, c -> c, capFn, List.of("AAA"));
        // 解析函数抛异常也不拖垮:兜底降级 empty。
        assertTrue(svc.marketCapOf("AAA").isEmpty());
    }

    @Test
    void latestCloseByCodeReturnsLastDayCloses() {
        FakeSource source = new FakeSource();
        source.byCode.put("AAA", series(30, 10));
        source.byCode.put("BBB", series(30, 20));

        MarketDataService svc = new MarketDataService(
                source, code -> code, code -> OptionalDouble.empty(), List.of("AAA", "BBB"));

        MarketPanel panel = svc.buildPanel();
        Map<String, Double> closes = svc.latestCloseByCode(panel);

        LocalDate last = svc.latestDay(panel);
        assertEquals(panel.closeOn("AAA", last), closes.get("AAA"), 1e-9);
        assertEquals(panel.closeOn("BBB", last), closes.get("BBB"), 1e-9);
    }

    /** 每次 updateBars sleep 模拟网络 IO,并发计数最大在飞线程数,用于验证并发与计时。 */
    static class SlowSource implements DataSource {
        final Map<String, List<Bar>> byCode = new ConcurrentHashMap<>();
        final long sleepMs;
        final AtomicInteger inFlight = new AtomicInteger(0);
        final AtomicInteger maxInFlight = new AtomicInteger(0);

        SlowSource(long sleepMs) {
            this.sleepMs = sleepMs;
        }

        @Override
        public List<Bar> updateBars(String code) {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return byCode.getOrDefault(code, List.of());
        }
    }

    @Test
    void buildPanelFetchesConcurrentlyAndMatchesSerialResult() {
        long sleep = 40;
        SlowSource source = new SlowSource(sleep);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String code = "S" + i;
            codes.add(code);
            source.byCode.put(code, series(30, 10 + i));
        }

        MarketDataService svc = new MarketDataService(source, c -> c, c -> OptionalDouble.empty(), codes);

        long t0 = System.nanoTime();
        MarketPanel panel = svc.buildPanel();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        // 正确性:所有 code 都进面板且最新日有收盘价(与串行预期一致)。
        LocalDate last = svc.latestDay(panel);
        for (String code : codes) {
            assertFalse(Double.isNaN(panel.closeOn(code, last)),
                    code + " 应在面板最新日有收盘价");
        }

        // 确实并发:同时在飞的线程数 > 1。
        assertTrue(source.maxInFlight.get() > 1,
                "应并发拉取, 实际最大并发=" + source.maxInFlight.get());

        // 墙钟 < 0.6× 串行(串行 = 8 * sleep)。阈值宽松防 CI 抖动。
        long serialMs = codes.size() * sleep;
        assertTrue(elapsedMs < 0.6 * serialMs,
                "并发墙钟 " + elapsedMs + "ms 应 < 0.6×串行 " + serialMs + "ms");
    }

    @Test
    void buildPanelIsolatesPerCodeFailures() {
        // BOOM 抛异常, EMPTY 返回空, 其余正常 —— 断言不整批失败。
        DataSource source = new DataSource() {
            @Override
            public List<Bar> updateBars(String code) {
                if (code.equals("BOOM")) {
                    throw new RuntimeException("simulated fetch failure");
                }
                if (code.equals("EMPTY")) {
                    return List.of();
                }
                return series(30, code.equals("AAA") ? 10 : 20);
            }
        };

        MarketDataService svc = new MarketDataService(
                source, c -> c, c -> OptionalDouble.empty(), List.of("AAA", "BOOM", "EMPTY", "BBB"));

        MarketPanel panel = svc.buildPanel();
        LocalDate last = svc.latestDay(panel);

        // 正常票照进面板。
        assertFalse(Double.isNaN(panel.closeOn("AAA", last)));
        assertFalse(Double.isNaN(panel.closeOn("BBB", last)));
        // 抛异常/空数据的票被跳过, 不污染面板。
        assertTrue(Double.isNaN(panel.closeOn("BOOM", last)));
        assertTrue(Double.isNaN(panel.closeOn("EMPTY", last)));
    }

    @Test
    void emptyPanelHasNullLatestDayAndEmptyCloses() {
        FakeSource source = new FakeSource(); // no data for anything
        MarketDataService svc = new MarketDataService(
                source, code -> code, code -> OptionalDouble.empty(), List.of("AAA"));

        MarketPanel panel = svc.buildPanel();

        assertNull(svc.latestDay(panel));
        assertTrue(svc.latestCloseByCode(panel).isEmpty());
    }
}
