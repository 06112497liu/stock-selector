package com.aistock.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Store extends BaseSqliteStore {

    public Store(String dbPath) {
        super(dbPath);
        initSchema();
    }

    public record Position(double shares, double avgCost) {
    }

    public record NavPoint(String date, double nav) {
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
        initSchema(List.of(posDdl, cashDdl, navDdl), "Failed to initialise ledger schema");
    }

    public Map<String, Position> getPositions() {
        String sql = "SELECT code, shares, avg_cost FROM positions ORDER BY code ASC";
        return executeQuery(sql, "Failed to read positions", rs -> {
            Map<String, Position> out = new LinkedHashMap<>();
            while (rs.next()) {
                out.put(rs.getString("code"),
                        new Position(rs.getDouble("shares"), rs.getDouble("avg_cost")));
            }
            return out;
        }, ps -> {});
    }

    public void upsertPosition(String code, double shares, double avgCost) {
        String sql = "INSERT INTO positions(code, shares, avg_cost) VALUES(?, ?, ?) "
                + "ON CONFLICT(code) DO UPDATE SET "
                + "shares = excluded.shares, avg_cost = excluded.avg_cost";
        executeUpdate(sql, "Failed to upsert position " + code, ps -> {
            ps.setString(1, code);
            ps.setDouble(2, shares);
            ps.setDouble(3, avgCost);
        });
    }

    public void removePosition(String code) {
        executeUpdate("DELETE FROM positions WHERE code = ?",
                "Failed to remove position " + code,
                ps -> ps.setString(1, code));
    }

    public void replaceAllPositions(Map<String, Position> positions) {
        executeTransaction(conn -> {
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
        }, "Failed to replace all positions");
    }

    public double getCash() {
        return executeQuery("SELECT amount FROM cash WHERE id = 1", "Failed to read cash", rs -> {
            if (rs.next()) {
                return rs.getDouble("amount");
            }
            return 0.0;
        }, ps -> {});
    }

    public void setCash(double amount) {
        String sql = "INSERT INTO cash(id, amount) VALUES(1, ?) "
                + "ON CONFLICT(id) DO UPDATE SET amount = excluded.amount";
        executeUpdate(sql, "Failed to set cash", ps -> ps.setDouble(1, amount));
    }

    public List<NavPoint> navHistory() {
        String sql = "SELECT date, nav FROM nav_history ORDER BY date ASC";
        return executeQuery(sql, "Failed to read nav history", rs -> {
            List<NavPoint> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new NavPoint(rs.getString("date"), rs.getDouble("nav")));
            }
            return out;
        }, ps -> {});
    }

    public void recordNav(String date, double nav) {
        String sql = "INSERT INTO nav_history(date, nav) VALUES(?, ?) "
                + "ON CONFLICT(date) DO UPDATE SET nav = excluded.nav";
        executeUpdate(sql, "Failed to record nav for " + date, ps -> {
            ps.setString(1, date);
            ps.setDouble(2, nav);
        });
    }

    public void clearNav() {
        executeUpdate("DELETE FROM nav_history", "Failed to clear nav history", ps -> {});
    }
}
