package com.aistock.datasource;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * {@link DataSource} backed by {@link YahooClient} for fetching and a
 * {@link BarCache} for persistence.
 *
 * <p>Update strategy:
 * <ul>
 *   <li>If the cache already holds data, the fetch window starts on the last
 *       cached date itself (re-pulling that day so an intraday snapshot can be
 *       overwritten with the settled close via the cache's UPSERT).</li>
 *   <li>Otherwise it starts at {@link #DEFAULT_START}.</li>
 *   <li>The window ends today.</li>
 *   <li>Any fetch failure (rate-limit, network) or an empty result degrades to
 *       returning the existing cached history without throwing.</li>
 * </ul>
 */
public class YahooSource implements DataSource {

    /** Start date used when nothing has been cached for a code yet. */
    public static final LocalDate DEFAULT_START = LocalDate.of(2018, 1, 1);

    private final YahooClient client;
    private final BarCache cache;

    public YahooSource(YahooClient client, BarCache cache) {
        this.client = client;
        this.cache = cache;
    }

    @Override
    public List<Bar> updateBars(String code) {
        Optional<LocalDate> last = cache.lastDate(code);
        // Re-pull the last cached day so its intraday close can be corrected.
        LocalDate start = last.orElse(DEFAULT_START);
        LocalDate end = LocalDate.now();

        List<Bar> fetched;
        try {
            fetched = client.fetchDaily(code, start, end);
        } catch (Exception e) {
            // Rate-limited / network failure: degrade to the cached history.
            return cache.load(code);
        }

        if (fetched == null || fetched.isEmpty()) {
            return cache.load(code);
        }

        cache.append(code, fetched);
        return cache.load(code);
    }
}
