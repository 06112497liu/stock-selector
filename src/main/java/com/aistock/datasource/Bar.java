package com.aistock.datasource;

import java.time.LocalDate;

/**
 * A single daily OHLCV bar.
 *
 * @param date   trading day (in the exchange's local time zone)
 * @param open   opening price
 * @param high   highest price
 * @param low    lowest price
 * @param close  closing price
 * @param volume traded volume
 */
public record Bar(
        LocalDate date,
        double open,
        double high,
        double low,
        double close,
        long volume
) {
}
