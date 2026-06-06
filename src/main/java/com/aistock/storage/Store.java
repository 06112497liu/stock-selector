package com.aistock.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账本存储(SQLite,按 market 隔离)。
 *
 * <p>每个 market(us/cn)一个独立 db 文件,放在 {@code market.cache-dir} 目录下
 * (如 {@code {cacheDir}/{market}_ledger.sqlite}),保证 Docker 卷 /data 持久化、
 * 容器重启账本不丢。
 *
 * <p>本账本<b>不参与选股 / 买入金额计算</b>,只用于:①对账(把账本改成券商真实持仓)
 * ②净值曲线展示 ③给 /signals 提供「我持有哪些 code + 入场价」来算「继续持有 / 该卖」。
 *
 * <p>表结构:
 * <pre>
 * positions(code TEXT PRIMARY KEY, shares REAL, avg_cost REAL)
 * cash(id INTEGER PRIMARY KEY CHECK(id=1), amount REAL)  -- 单行
 * nav_history(date TEXT PRIMARY KEY, nav REAL)
 * </pre>
 *
 * <p>每个操作打开一个短生命周期 JDBC 连接(同 {@link com.aistock.datasource.BarCache} 写法),
 * 无需外部管理连接生命周期。
 */
public final class Store {

    private final String url;

    /**
     * @param dbPath 账本 SQLite 文件路径(首次使用时自动创建)
     */
    public Store(String dbPath) {
        this.url = "jdbc:sqlite:" + dbPath;
        initSchema();
    }

    /** 持仓记录:股数 + 成本价(avg_cost,对账后作为止损入场价)。 */
    public record Position(double shares, double avgCost) {
    }

    /** 净值历史一笔:交易日 + 当日净值。 */
    public record NavPoint(String date, double nav) {
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url);
    }

    private void initSchema() {
        String posDdl = "CREATE TABLE IF NOT EXISTS positions ("
                + "code TEXT PRIMARY KEY, "
                + "shares REAL, "
                + "avg_cost REAL)";
        String cashDdl = "CREATE TABLE IF NOT EXISTS cash ("
                + "id INTEGER PRIMARY KEY CHECK(id = 1), "
                + "amount REAL)";
        String navDdl = "CREATE TABLE IF NOT EXISTS nav_history ("
                + "date TEXT PRIMARY KEY, "
                + "nav REAL)";
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute(posDdl);
            st.execute(cashDdl);
            st.execute(navDdl);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialise ledger schema", e);
        }
    }

    // ---- positions ---------------------------------------------------------

    /** 当前全部持仓:code -> Position(shares, avgCost)。空库返回空 map。 */
    public Map<String, Position> getPositions() {
        String sql = "SELECT code, shares, avg_cost FROM positions ORDER BY code ASC";
        Map<String, Position> out = new LinkedHashMap<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString("code"),
                        new Position(rs.getDouble("shares"), rs.getDouble("avg_cost")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read positions", e);
        }
        return out;
    }

    /** 新增或更新一笔持仓(按 code 主键 upsert)。 */
    public void upsertPosition(String code, double shares, double avgCost) {
        String sql = "INSERT INTO positions(code, shares, avg_cost) VALUES(?, ?, ?) "
                + "ON CONFLICT(code) DO UPDATE SET "
                + "shares = excluded.shares, avg_cost = excluded.avg_cost";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            ps.setDouble(2, shares);
            ps.setDouble(3, avgCost);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to upsert position " + code, e);
        }
    }

    /** 删除一笔持仓(无则 no-op)。 */
    public void removePosition(String code) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM positions WHERE code = ?")) {
            ps.setString(1, code);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to remove position " + code, e);
        }
    }

    /**
     * 用给定持仓<b>整体替换</b>账本(先清空再写,对账用)。
     *
     * @param positions code -> Position(shares, avgCost);为空则清空所有持仓
     */
    public void replaceAllPositions(Map<String, Position> positions) {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try (Statement clear = conn.createStatement()) {
                clear.executeUpdate("DELETE FROM positions");
            }
            if (positions != null && !positions.isEmpty()) {
                String sql = "INSERT INTO positions(code, shares, avg_cost) VALUES(?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (Map.Entry<String, Position> e : positions.entrySet()) {
                        ps.setString(1, e.getKey());
                        ps.setDouble(2, e.getValue().shares());
                        ps.setDouble(3, e.getValue().avgCost());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to replace all positions", e);
        }
    }

    // ---- cash --------------------------------------------------------------

    /** 当前现金;空库默认 0。 */
    public double getCash() {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT amount FROM cash WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getDouble("amount");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read cash", e);
        }
        return 0.0;
    }

    /** 设置现金(单行 upsert)。 */
    public void setCash(double amount) {
        String sql = "INSERT INTO cash(id, amount) VALUES(1, ?) "
                + "ON CONFLICT(id) DO UPDATE SET amount = excluded.amount";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, amount);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to set cash", e);
        }
    }

    // ---- nav_history -------------------------------------------------------

    /** 净值历史,按日期升序。空库返回空 list。 */
    public List<NavPoint> navHistory() {
        String sql = "SELECT date, nav FROM nav_history ORDER BY date ASC";
        List<NavPoint> out = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new NavPoint(rs.getString("date"), rs.getDouble("nav")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read nav history", e);
        }
        return out;
    }

    /** 记一笔当日净值(按 date 主键 upsert,同一天只保留一笔)。 */
    public void recordNav(String date, double nav) {
        String sql = "INSERT INTO nav_history(date, nav) VALUES(?, ?) "
                + "ON CONFLICT(date) DO UPDATE SET nav = excluded.nav";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, date);
            ps.setDouble(2, nav);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record nav for " + date, e);
        }
    }

    /** 清空净值历史(对账时重置净值基准,避免旧的虚假峰值污染回撤护栏)。 */
    public void clearNav() {
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM nav_history");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear nav history", e);
        }
    }
}
