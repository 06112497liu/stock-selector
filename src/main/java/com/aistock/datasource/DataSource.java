package com.aistock.datasource;

import java.util.List;

/**
 * A source of daily bars that can incrementally update and return the full
 * cached history for an instrument.
 */
public interface DataSource {

    /**
     * Brings the cached history for {@code code} up to date and returns it.
     *
     * <p>Implementations should fetch only the missing range where possible and
     * must degrade gracefully (returning whatever is already cached) when the
     * upstream provider is unavailable or rate-limited.</p>
     *
     * @param code instrument code
     * @return the full cached history, oldest-first
     */
    List<Bar> updateBars(String code);
}
