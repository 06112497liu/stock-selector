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

class YahooSourceTest {

    @TempDir
    Path tmp;

    private BarCache newCache() {
        return new BarCache(tmp.resolve("bars.db").toString());
    }

    /**
     * Fake {@link YahooClient} that records the requested window and returns a
     * canned response (or throws). YahooClient exposes a public no-arg
     * constructor, so we subclass it and override the network method
     * {@code fetchDaily} -- no real HTTP is performed.
     */
    static class FakeYahooClient extends YahooClient {
        LocalDate capturedFrom;
        LocalDate capturedTo;
        int calls;
        private final List<Bar> toReturn;
        private final boolean throwRateLimit;

        FakeYahooClient(List<Bar> toReturn, boolean throwRateLimit) {
            this.toReturn = toReturn;
            this.throwRateLimit = throwRateLimit;
        }

        @Override
        public List<Bar> fetchDaily(String symbol, LocalDate from, LocalDate to) throws IOException {
            this.calls++;
            this.capturedFrom = from;
            this.capturedTo = to;
            if (throwRateLimit) {
                throw new IOException("Yahoo request failed: HTTP 429 for " + symbol);
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
        FakeYahooClient client = new FakeYahooClient(synthetic, false);
        YahooSource source = new YahooSource(client, cache);

        List<Bar> result = source.updateBars("AAPL");

        assertEquals(YahooSource.DEFAULT_START, client.capturedFrom);
        assertEquals(LocalDate.now(), client.capturedTo);
        assertEquals(synthetic, result);
        // Persisted.
        assertEquals(synthetic, cache.load("AAPL"));
    }

    @Test
    void incrementalUpdateStartsFromLastCachedDate() {
        BarCache cache = newCache();
        LocalDate lastCached = LocalDate.of(2024, 5, 10);
        cache.append("AAPL", List.of(new Bar(lastCached, 1, 1, 1, 1, 1)));

        FakeYahooClient client = new FakeYahooClient(
                List.of(new Bar(LocalDate.of(2024, 5, 13), 2, 2, 2, 2, 2)), false);
        YahooSource source = new YahooSource(client, cache);

        source.updateBars("AAPL");

        // Window starts ON the last cached day so it can be re-pulled.
        assertEquals(lastCached, client.capturedFrom);
        assertEquals(LocalDate.now(), client.capturedTo);
    }

    @Test
    void rateLimitDegradesToCache() {
        BarCache cache = newCache();
        Bar cached = new Bar(LocalDate.of(2024, 5, 10), 1, 1, 1, 1, 1);
        cache.append("AAPL", List.of(cached));

        FakeYahooClient client = new FakeYahooClient(null, true);
        YahooSource source = new YahooSource(client, cache);

        List<Bar> result = source.updateBars("AAPL");

        assertEquals(1, client.calls);
        assertNotNull(result);
        assertEquals(List.of(cached), result);
    }

    @Test
    void emptyFetchDegradesToCache() {
        BarCache cache = newCache();
        Bar cached = new Bar(LocalDate.of(2024, 5, 10), 1, 1, 1, 1, 1);
        cache.append("AAPL", List.of(cached));

        FakeYahooClient client = new FakeYahooClient(List.of(), false);
        YahooSource source = new YahooSource(client, cache);

        List<Bar> result = source.updateBars("AAPL");

        assertEquals(List.of(cached), result);
    }

    @Test
    void reFetchedLastDayOverwritesIntradayClose() {
        BarCache cache = newCache();
        LocalDate day = LocalDate.of(2024, 5, 10);
        // Intraday snapshot already cached.
        cache.append("AAPL", List.of(new Bar(day, 10, 11, 9, 10.2, 500)));

        // Re-fetch returns the same day with a settled close plus a new day.
        Bar settled = new Bar(day, 10, 11.5, 8.9, 10.8, 1500);
        Bar nextDay = new Bar(LocalDate.of(2024, 5, 13), 11, 12, 10, 11.5, 800);
        FakeYahooClient client = new FakeYahooClient(List.of(settled, nextDay), false);
        YahooSource source = new YahooSource(client, cache);

        List<Bar> result = source.updateBars("AAPL");

        assertEquals(2, result.size());
        assertEquals(settled, result.get(0));
        assertEquals(nextDay, result.get(1));
        // No duplicate row for the re-pulled day.
        assertTrue(result.stream().filter(b -> b.date().equals(day)).count() == 1);
    }
}
