package com.aistock.datasource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link KlinePeriod} 映射与 fromCode 降级。无网络。 */
class KlinePeriodTest {

    @Test
    void fromCodeMapsEachCode() {
        assertEquals(KlinePeriod.MIN_1, KlinePeriod.fromCode("1m"));
        assertEquals(KlinePeriod.MIN_5, KlinePeriod.fromCode("5m"));
        assertEquals(KlinePeriod.MIN_15, KlinePeriod.fromCode("15m"));
        assertEquals(KlinePeriod.MIN_30, KlinePeriod.fromCode("30m"));
        assertEquals(KlinePeriod.MIN_60, KlinePeriod.fromCode("60m"));
        assertEquals(KlinePeriod.DAY, KlinePeriod.fromCode("1d"));
        assertEquals(KlinePeriod.WEEK, KlinePeriod.fromCode("1wk"));
        assertEquals(KlinePeriod.MONTH, KlinePeriod.fromCode("1mo"));
    }

    @Test
    void fromCodeIsCaseInsensitive() {
        assertEquals(KlinePeriod.WEEK, KlinePeriod.fromCode("1WK"));
    }

    @Test
    void illegalOrNullCodeDefaultsToDay() {
        assertEquals(KlinePeriod.DAY, KlinePeriod.fromCode(null));
        assertEquals(KlinePeriod.DAY, KlinePeriod.fromCode(""));
        assertEquals(KlinePeriod.DAY, KlinePeriod.fromCode("nonsense"));
        assertEquals(KlinePeriod.DAY, KlinePeriod.fromCode("2h"));
    }

    @Test
    void yahooAndEastMoneyMappings() {
        assertEquals("5m", KlinePeriod.MIN_5.yahooInterval());
        assertEquals("5d", KlinePeriod.MIN_5.yahooRange());
        assertEquals("5", KlinePeriod.MIN_5.eastMoneyKlt());

        assertEquals("1d", KlinePeriod.DAY.yahooInterval());
        assertEquals("1y", KlinePeriod.DAY.yahooRange());
        assertEquals("101", KlinePeriod.DAY.eastMoneyKlt());

        assertEquals("1mo", KlinePeriod.MONTH.yahooInterval());
        assertEquals("5y", KlinePeriod.MONTH.yahooRange());
        assertEquals("103", KlinePeriod.MONTH.eastMoneyKlt());
    }

    @Test
    void intradayFlag() {
        assertTrue(KlinePeriod.MIN_1.isIntraday());
        assertTrue(KlinePeriod.MIN_60.isIntraday());
        assertFalse(KlinePeriod.DAY.isIntraday());
        assertFalse(KlinePeriod.WEEK.isIntraday());
        assertFalse(KlinePeriod.MONTH.isIntraday());
    }
}
