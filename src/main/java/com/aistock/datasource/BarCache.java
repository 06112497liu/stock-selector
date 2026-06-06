package com.aistock.datasource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite-backed cache for daily {@link Bar}s.
 *
 * <p>Schema:
 * <pre>
 * bars(code TEXT, date TEXT, open REAL, high REAL, low REAL, close REAL,
 *      volume INTEGER, PRIMARY KEY(code, date))
 * </pre>
 *
 * <p>Each operation opens a short-lived JDBC connection to the database file
 * given at construction time, so the cache is safe to share across calls
 * without managing connection lifecycle externally.</p>
 *
 * <p><b>Thread-safety:</b> because every operation uses its own connection
 * (JDBC {@link Connection}s are not thread-safe) and never shares one, the
 * cache may be used concurrently from multiple threads. Concurrent writes to
 * the single SQLite file are serialised by SQLite's file-level write lock;
 * each connection sets {@code PRAGMA busy_timeout} so a writer waits for the
 * lock instead of failing fast with {@code SQLITE_BUSY}.</p>
 */
public class BarCache {

    /** Milliseconds a connection waits on a locked database before giving up. */
    private static final int BUSY_TIMEOUT_MS = 5000;

    private final String url;

    /**
     * @param dbPath filesystem path to the SQLite database file
     *               (created on first use if it does not exist)
     */
    public BarCache(String dbPath) {
        this.url = "jdbc:sqlite:" + dbPath;
        initSchema();
    }

    private Connection connect() throws SQLException {
        Connection conn = DriverManager.getConnection(url);
        // Wait for a contended write lock rather than failing with SQLITE_BUSY,
        // so concurrent appends (different codes, same file) serialise safely.
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);
        } catch (SQLException e) {
            conn.close();
            throw e;
        }
        return conn;
    }

    private void initSchema() {
        String ddl = "CREATE TABLE IF NOT EXISTS bars ("
                + "code TEXT NOT NULL, "
                + "date TEXT NOT NULL, "
                + "open REAL, "
                + "high REAL, "
                + "low REAL, "
                + "close REAL, "
                + "volume INTEGER, "
                + "PRIMARY KEY(code, date))";
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute(ddl);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialise bar cache schema", e);
        }
    }

    /**
     * Loads all cached bars for {@code code}, ordered by date ascending.
     *
     * @param code instrument code
     * @return bars oldest-first; empty if none cached
     */
    public List<Bar> load(String code) {
        String sql = "SELECT date, open, high, low, close, volume "
                + "FROM bars WHERE code = ? ORDER BY date ASC";
        List<Bar> bars = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    bars.add(new Bar(
                            LocalDate.parse(rs.getString("date")),
                            rs.getDouble("open"),
                            rs.getDouble("high"),
                            rs.getDouble("low"),
                            rs.getDouble("close"),
                            rs.getLong("volume")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load bars for " + code, e);
        }
        return bars;
    }

    /**
     * Inserts (or updates) the given bars for {@code code}.
     *
     * <p>Conflicts on the {@code (code, date)} primary key overwrite the existing
     * row with the new values. This lets a re-fetch of the latest trading day
     * replace an intraday snapshot with the settled close.</p>
     *
     * @param code instrument code
     * @param bars bars to upsert (no-op if empty)
     */
    public void append(String code, List<Bar> bars) {
        if (bars == null || bars.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO bars(code, date, open, high, low, close, volume) "
                + "VALUES(?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT(code, date) DO UPDATE SET "
                + "open = excluded.open, "
                + "high = excluded.high, "
                + "low = excluded.low, "
                + "close = excluded.close, "
                + "volume = excluded.volume";
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (Bar bar : bars) {
                    ps.setString(1, code);
                    ps.setString(2, bar.date().toString());
                    ps.setDouble(3, bar.open());
                    ps.setDouble(4, bar.high());
                    ps.setDouble(5, bar.low());
                    ps.setDouble(6, bar.close());
                    ps.setLong(7, bar.volume());
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to append bars for " + code, e);
        }
    }

    /**
     * Returns the most recent cached trading date for {@code code}, if any.
     *
     * @param code instrument code
     * @return the latest date, or empty if nothing is cached
     */
    public Optional<LocalDate> lastDate(String code) {
        String sql = "SELECT MAX(date) AS max_date FROM bars WHERE code = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String maxDate = rs.getString("max_date");
                    if (maxDate != null) {
                        return Optional.of(LocalDate.parse(maxDate));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read last date for " + code, e);
        }
        return Optional.empty();
    }
}
