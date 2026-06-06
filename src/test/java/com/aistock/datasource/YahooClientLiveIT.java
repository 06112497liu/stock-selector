package com.aistock.datasource;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Optional live integration test that actually hits Yahoo Finance.
 *
 * <p>It is disabled by default so the build stays green offline / when rate-limited.
 * Enable with {@code -Dyahoo.live=true}. Any network or rate-limit failure aborts
 * (rather than fails) the test via {@link Assumptions}.</p>
 */
class YahooClientLiveIT {

    @Test
    void fetchesRealAaplBars() {
        Assumptions.assumeTrue(
                Boolean.getBoolean("yahoo.live"),
                "Live Yahoo test skipped (run with -Dyahoo.live=true to enable)");

        YahooClient client = new YahooClient();
        List<Bar> bars;
        try {
            LocalDate to = LocalDate.now();
            LocalDate from = to.minusDays(14);
            bars = client.fetchDaily("AAPL", from, to);
        } catch (Exception e) {
            Assumptions.abort("Yahoo unreachable / rate-limited: " + e.getMessage());
            return; // unreachable, keeps the compiler happy
        }

        assertNotNull(bars);
        assertFalse(bars.isEmpty(), "expected at least one AAPL bar");
        Bar b = bars.get(0);
        assertTrue(b.high() >= b.low(), "high must be >= low");
        assertTrue(b.close() > 0, "close must be positive");
    }
}
