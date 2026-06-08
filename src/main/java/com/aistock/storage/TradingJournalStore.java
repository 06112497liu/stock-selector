package com.aistock.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 交易日记存储(SQLite,独立库,放 cache-dir;
 * 保证 Docker 卷 /data 持久化、容器重启不丢。
 *
 * <p>表结构:
 * <pre>
 * trading_journal(
 *   id INTEGER PRIMARY KEY AUTOINCREMENT,
 *   market TEXT NOT NULL,
 *   operation_type TEXT NOT NULL,
 *   code TEXT NOT NULL,
 *   name TEXT,
 *   quantity REAL NOT NULL,
 *   price REAL NOT NULL,
 *   trade_date TEXT NOT NULL,
 *   reason TEXT,
 *   market_env TEXT,
 *   notes TEXT,
 *   system_score REAL,
 *   created_at TEXT
 * )
 * </pre>
 *
 * <p>{@code operation_type} 取值 "BUY" | "SELL"。
 * {@code market} 取值 "us" | "cn" | "wl_xxx",标识这笔交易属于哪个市场/自选股分组。
 * {@code system_score} 记录交易当时系统给该标的的评分(可能为 NULL)。
 */
public final class TradingJournalStore {

    public enum OperationType {
        BUY, SELL
    }

    /**
     * 单条交易日记记录。
     *
     * @param id            主键 id
     * @param market        所属市场("us" | "cn" | "wl_xxx")
     * @param operationType 操作类型(BUY/SELL)
     * @param code          股票代码
     * @param name          股票名称(可为 null
     * @param quantity    数量(股数)
     * @param price         成交价
     * @param tradeDate     交易日期时间
     * @param reason        操作理由
     * @param marketEnv    当时市场环境描述
     * @param notes         事后备注
     * @param systemScore   当时系统给该标的的评分(可为 null)
     * @param createdAt    记录创建时间
     */
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

    private final String url;

    public TradingJournalStore(String dbPath) {
        this.url = "jdbc:sqlite:" + dbPath;
        initSchema();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url);
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
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute(ddl);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialise trading journal schema", e);
        }
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

    /**
     * 新增一笔交易记录并返回主键 id。
     */
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
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
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
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to add trading journal entry", e);
        }
        return -1;
    }

    /**
     * 更新一笔交易记录的备注字段(事后补记)。
     */
    public void updateNotes(long id, String notes) {
        String sql = "UPDATE trading_journal SET notes = ? WHERE id = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, notes);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update notes for entry " + id, e);
        }
    }

    /**
     * 删除一笔交易记录。
     */
    public void deleteEntry(long id) {
        String sql = "DELETE FROM trading_journal WHERE id = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete trading journal entry " + id, e);
        }
    }

    /**
     * 查询所有记录,按交易日期倒序(最新在前)。
     */
    public List<JournalEntry> listAll() {
        return listByMarket(null);
    }

    /**
     * 按市场查询记录,按交易日期倒序。
     *
     * @param market 为 null 时返回全部
     */
    public List<JournalEntry> listByMarket(String market) {
        String sql;
        boolean filter = market != null && !market.isBlank();
        if (filter) {
            sql = "SELECT id, market, operation_type, code, name, quantity, price, trade_date, "
                    + "reason, market_env, notes, system_score, created_at "
                    + "FROM trading_journal WHERE market = ? ORDER BY trade_date DESC, id DESC";
        } else {
            sql = "SELECT id, market, operation_type, code, name, quantity, price, trade_date, "
                    + "reason, market_env, notes, system_score, created_at "
                    + "FROM trading_journal ORDER BY trade_date DESC, id DESC";
        }
        List<JournalEntry> out = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (filter) {
                ps.setString(1, market);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Double score = rs.getObject("system_score") != null
                            ? rs.getDouble("system_score")
                            : null;
                    out.add(new JournalEntry(
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
                    ));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list trading journal entries", e);
        }
        return out;
    }

    /**
     * 按股票代码查询所有记录,按交易日期倒序。
     */
    public List<JournalEntry> listByCode(String code) {
        String sql = "SELECT id, market, operation_type, code, name, quantity, price, trade_date, "
                + "reason, market_env, notes, system_score, created_at "
                + "FROM trading_journal WHERE code = ? ORDER BY trade_date DESC, id DESC";
        List<JournalEntry> out = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Double score = rs.getObject("system_score") != null
                            ? rs.getDouble("system_score")
                            : null;
                    out.add(new JournalEntry(
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
                    ));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list trading journal entries for " + code, e);
        }
        return out;
    }

    /**
     * 按 id 查询单条记录,不存在返回 null。
     */
    public JournalEntry getById(long id) {
        String sql = "SELECT id, market, operation_type, code, name, quantity, price, trade_date, "
                + "reason, market_env, notes, system_score, created_at "
                + "FROM trading_journal WHERE id = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
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
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to get trading journal entry " + id, e);
        }
        return null;
    }
}
