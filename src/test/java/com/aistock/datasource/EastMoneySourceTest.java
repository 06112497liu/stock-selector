package com.aistock.datasource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EastMoneySourceTest {

    @TempDir
    Path tmp;

    private BarCache newCache() {
        return new BarCache(tmp.resolve("bars.db").toString());
    }

    /**
     * Fake {@link EastMoneyClient} that records the requested window and returns
     * a canned response (or throws). EastMoneyClient exposes a public no-arg
     * constructor, so we subclass it and override the network method
     * {@code fetchDaily} -- no real HTTP is performed.
     */
    static class FakeEastMoneyClient extends EastMoneyClient {
        LocalDate capturedFrom;
        LocalDate capturedTo;
        String capturedCode;
        int calls;
        private final List<Bar> toReturn;
        private final boolean throwRateLimit;

        FakeEastMoneyClient(List<Bar> toReturn, boolean throwRateLimit) {
            this.toReturn = toReturn;
            this.throwRateLimit = throwRateLimit;
        }

        @Override
        public List<Bar> fetchDaily(String code, LocalDate from, LocalDate to) throws IOException {
            this.calls++;
            this.capturedCode = code;
            this.capturedFrom = from;
            this.capturedTo = to;
            if (throwRateLimit) {
                throw new IOException("East Money request failed: HTTP 429 for " + code);
            }
            return toReturn;
        }
    }

    @Test
    void firstUpdateFetchesFromDefaultStartAndCaches() {
        BarCache cache = newCache();
        List<Bar> synthetic = List.of(
                new Bar(LocalDate.of(2018, 1, 2), 10, 11, 9, 10.5, 100),
                new Bar(LocalDate.of(2018, 1, 3), 10.5, 12, 10, 11.5, 200)
        );
        FakeEastMoneyClient client = new FakeEastMoneyClient(synthetic, false);
        EastMoneySource source = new EastMoneySource(client, cache);

        List<Bar> result = source.updateBars("600519");

        assertEquals("600519", client.capturedCode);
        assertEquals(EastMoneySource.DEFAULT_START, client.capturedFrom);
        assertEquals(LocalDate.now(), client.capturedTo);
        assertEquals(synthetic, result);
        assertEquals(synthetic, cache.load("600519"));
    }

    @Test
    void incrementalUpdateStartsFromLastCachedDate() {
        BarCache cache = newCache();
        LocalDate lastCached = LocalDate.of(2024, 5, 10);
        cache.append("600519", List.of(new Bar(lastCached, 1, 1, 1, 1, 1)));

        FakeEastMoneyClient client = new FakeEastMoneyClient(
                List.of(new Bar(LocalDate.of(2024, 5, 13), 2, 2, 2, 2, 2)), false);
        EastMoneySource source = new EastMoneySource(client, cache);

        source.updateBars("600519");

        // Window starts ON the last cached day so it can be re-pulled.
        assertEquals(lastCached, client.capturedFrom);
        assertEquals(LocalDate.now(), client.capturedTo);
    }

    @Test
    void rateLimitDegradesToCache() {
        BarCache cache = newCache();
        Bar cached = new Bar(LocalDate.of(2024, 5, 10), 1, 1, 1, 1, 1);
        cache.append("600519", List.of(cached));

        FakeEastMoneyClient client = new FakeEastMoneyClient(null, true);
        EastMoneySource source = new EastMoneySource(client, cache);

        List<Bar> result = source.updateBars("600519");

        assertEquals(1, client.calls);
        assertNotNull(result);
        assertEquals(List.of(cached), result);
    }

    @Test
    void emptyFetchDegradesToCache() {
        BarCache cache = newCache();
        Bar cached = new Bar(LocalDate.of(2024, 5, 10), 1, 1, 1, 1, 1);
        cache.append("600519", List.of(cached));

        FakeEastMoneyClient client = new FakeEastMoneyClient(List.of(), false);
        EastMoneySource source = new EastMoneySource(client, cache);

        List<Bar> result = source.updateBars("600519");

        assertEquals(List.of(cached), result);
    }

    @Test
    void reFetchedLastDayOverwritesIntradayClose() {
        BarCache cache = newCache();
        LocalDate day = LocalDate.of(2024, 5, 10);
        // Intraday snapshot already cached.
        cache.append("600519", List.of(new Bar(day, 10, 11, 9, 10.2, 500)));

        // Re-fetch returns the same day with a settled close plus a new day.
        Bar settled = new Bar(day, 10, 11.5, 8.9, 10.8, 1500);
        Bar nextDay = new Bar(LocalDate.of(2024, 5, 13), 11, 12, 10, 11.5, 800);
        FakeEastMoneyClient client = new FakeEastMoneyClient(List.of(settled, nextDay), false);
        EastMoneySource source = new EastMoneySource(client, cache);

        List<Bar> result = source.updateBars("600519");

        assertEquals(2, result.size());
        assertEquals(settled, result.get(0));
        assertEquals(nextDay, result.get(1));
        // No duplicate row for the re-pulled day.
        assertTrue(result.stream().filter(b -> b.date().equals(day)).count() == 1);
    }
}
