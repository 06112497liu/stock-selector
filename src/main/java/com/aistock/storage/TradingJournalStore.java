package com.aistock.storage;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class TradingJournalStore extends BaseSqliteStore {

    public enum OperationType {
        BUY, SELL
    }

    public record JournalEntry(
            long id,
            String market,
            OperationType operationType,
            String code,
            String name,
            double quantity,
            double price,
            LocalDateTime tradeDate,
            String reason,
            String marketEnv,
            String notes,
            Double systemScore,
            LocalDateTime createdAt
    ) {
        public String tradeDateDisplay() {
            return tradeDate != null
                    ? tradeDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    : "";
        }

        public String createdAtDisplay() {
            return createdAt != null
                    ? createdAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    : "";
        }

        public boolean hasScore() {
            return systemScore != null && !Double.isNaN(systemScore);
        }

        public double amount() {
            return quantity * price;
        }
    }

    public TradingJournalStore(String dbPath) {
        super(dbPath);
        initSchema();
    }

    private void initSchema() {
        String ddl = "CREATE TABLE IF NOT EXISTS trading_journal ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "market TEXT NOT NULL, "
                + "operation_type TEXT NOT NULL, "
                + "code TEXT NOT NULL, "
                + "name TEXT, "
                + "quantity REAL NOT NULL, "
                + "price REAL NOT NULL, "
                + "trade_date TEXT NOT NULL, "
                + "reason TEXT, "
                + "market_env TEXT, "
                + "notes TEXT, "
                + "system_score REAL, "
                + "created_at TEXT)";
        initSchema(List.of(ddl), "Failed to initialise trading journal schema");
    }

    private static String nowIso() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private static String toIso(LocalDateTime dt) {
        return dt == null ? null : dt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private static LocalDateTime parseIso(String s) {
        if (s == null) return null;
        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }

    private static JournalEntry mapEntry(java.sql.ResultSet rs) throws java.sql.SQLException {
        Double score = rs.getObject("system_score") != null
                ? rs.getDouble("system_score")
                : null;
        return new JournalEntry(
                rs.getLong("id"),
                rs.getString("market"),
                OperationType.valueOf(rs.getString("operation_type")),
                rs.getString("code"),
                rs.getString("name"),
                rs.getDouble("quantity"),
                rs.getDouble("price"),
                parseIso(rs.getString("trade_date")),
                rs.getString("reason"),
                rs.getString("market_env"),
                rs.getString("notes"),
                score,
                parseIso(rs.getString("created_at"))
        );
    }

    public long addEntry(String market,
                         OperationType operationType,
                         String code,
                         String name,
                         double quantity,
                         double price,
                         LocalDateTime tradeDate,
                         String reason,
                         String marketEnv,
                         String notes,
                         Double systemScore) {
        String sql = "INSERT INTO trading_journal(market, operation_type, code, name, quantity, price, "
                + "trade_date, reason, market_env, notes, system_score, created_at) "
                + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return executeInsertReturnKey(sql, "Failed to add trading journal entry", ps -> {
            ps.setString(1, market);
            ps.setString(2, operationType.name());
            ps.setString(3, code);
            ps.setString(4, name);
            ps.setDouble(5, quantity);
            ps.setDouble(6, price);
            ps.setString(7, toIso(tradeDate != null ? tradeDate : LocalDateTime.now()));
            ps.setString(8, reason);
            ps.setString(9, marketEnv);
            ps.setString(10, notes);
            if (systemScore != null) {
                ps.setDouble(11, systemScore);
            } else {
                ps.setNull(11, java.sql.Types.REAL);
            }
            ps.setString(12, nowIso());
        });
    }

    public void updateNotes(long id, String notes) {
        String sql = "UPDATE trading_journal SET notes = ? WHERE id = ?";
        executeUpdate(sql, "Failed to update notes for entry " + id, ps -> {
            ps.setString(1, notes);
            ps.setLong(2, id);
        });
    }

    public void deleteEntry(long id) {
        String sql = "DELETE FROM trading_journal WHERE id = ?";
        executeUpdate(sql, "Failed to delete trading journal entry " + id, ps -> ps.setLong(1, id));
    }

    public List<JournalEntry> listAll() {
        return listByMarket(null);
    }

    public List<JournalEntry> listByMarket(String market) {
        boolean filter = market != null && !market.isBlank();
        String baseSql = "SELECT id, market, operation_type, code, name, quantity, price, trade_date, "
                + "reason, market_env, notes, system_score, created_at FROM trading_journal";
        String sql = filter
                ? baseSql + " WHERE market = ? ORDER BY trade_date DESC, id DESC"
                : baseSql + " ORDER BY trade_date DESC, id DESC";
        return executeQuery(sql, "Failed to list trading journal entries", rs -> {
            List<JournalEntry> out = new ArrayList<>();
            while (rs.next()) {
                out.add(mapEntry(rs));
            }
            return out;
        }, ps -> {
            if (filter) {
                ps.setString(1, market);
            }
        });
    }

    public List<JournalEntry> listByCode(String code) {
        String sql = "SELECT id, market, operation_type, code, name, quantity, price, trade_date, "
                + "reason, market_env, notes, system_score, created_at "
                + "FROM trading_journal WHERE code = ? ORDER BY trade_date DESC, id DESC";
        return executeQuery(sql, "Failed to list trading journal entries for " + code, rs -> {
            List<JournalEntry> out = new ArrayList<>();
            while (rs.next()) {
                out.add(mapEntry(rs));
            }
            return out;
        }, ps -> ps.setString(1, code));
    }

    public JournalEntry getById(long id) {
        String sql = "SELECT id, market, operation_type, code, name, quantity, price, trade_date, "
                + "reason, market_env, notes, system_score, created_at "
                + "FROM trading_journal WHERE id = ?";
        return executeQuery(sql, "Failed to get trading journal entry " + id, rs -> {
            if (rs.next()) {
                return mapEntry(rs);
            }
            return null;
        }, ps -> ps.setLong(1, id));
    }
}
