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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自选股分组与股票存储(SQLite,独立库,放 cache-dir;
 * 保证 Docker 卷 /data 持久化、容器重启不丢。
 *
 * <p>表结构:
 * <pre>
 * watchlist_groups(group_id TEXT PRIMARY KEY, group_name TEXT NOT NULL,
 *                 market_type TEXT NOT NULL, created_at TEXT)
 * watchlist_stocks(group_id TEXT, code TEXT, name TEXT,
 *                  PRIMARY KEY (group_id, code))
 * </pre>
 *
 * <p>{@code market_type} 取值 "us" | "cn",决定该分组用 Yahoo 还是 EastMoney 数据源。
 * 自选股分组 id 统一加 "wl_" 前缀,与内置 "us" / "cn" 区分开,
 * 作为 SignalService/PanelCache 的 market key。
 */
public final class WatchlistStore {

    public record WatchlistGroup(String groupId, String groupName, String marketType, LocalDateTime createdAt) {
    }

    public record WatchlistStock(String code, String name) {
    }

    public static final String PREFIX = "wl_";

    private final String url;

    public WatchlistStore(String dbPath) {
        this.url = "jdbc:sqlite:" + dbPath;
        initSchema();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url);
    }

    private void initSchema() {
        String groupDdl = "CREATE TABLE IF NOT EXISTS watchlist_groups ("
                + "group_id TEXT PRIMARY KEY, "
                + "group_name TEXT NOT NULL, "
                + "market_type TEXT NOT NULL, "
                + "created_at TEXT)";
        String stockDdl = "CREATE TABLE IF NOT EXISTS watchlist_stocks ("
                + "group_id TEXT, "
                + "code TEXT, "
                + "name TEXT, "
                + "PRIMARY KEY (group_id, code))";
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute(groupDdl);
            st.execute(stockDdl);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialise watchlist schema", e);
        }
    }

    private static String nowIso() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    // ---- groups ----------------------------------------------------------

    public List<WatchlistGroup> listGroups() {
        String sql = "SELECT group_id, group_name, market_type, created_at FROM watchlist_groups ORDER BY created_at ASC";
        List<WatchlistGroup> out = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String createdAtStr = rs.getString("created_at");
                out.add(new WatchlistGroup(
                        rs.getString("group_id"),
                        rs.getString("group_name"),
                        rs.getString("market_type"),
                        createdAtStr != null
                                ? LocalDateTime.parse(createdAtStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                : null));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list watchlist groups", e);
        }
        return out;
    }

    public WatchlistGroup getGroup(String groupId) {
        String sql = "SELECT group_id, group_name, market_type, created_at FROM watchlist_groups WHERE group_id = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new WatchlistGroup(
                            rs.getString("group_id"),
                            rs.getString("group_name"),
                            rs.getString("market_type"),
                            rs.getString("created_at") != null
                                    ? LocalDateTime.parse(rs.getString("created_at"), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                    : null);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to get watchlist group", e);
        }
        return null;
    }

    public void createGroup(String groupId, String groupName, String marketType) {
        String sql = "INSERT INTO watchlist_groups(group_id, group_name, market_type, created_at) VALUES(?, ?, ?, ?)";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupId);
            ps.setString(2, groupName);
            ps.setString(3, marketType);
            ps.setString(4, nowIso());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create watchlist group " + groupId, e);
        }
    }

    public void renameGroup(String groupId, String newName) {
        String sql = "UPDATE watchlist_groups SET group_name = ? WHERE group_id = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newName);
            ps.setString(2, groupId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to rename watchlist group " + groupId, e);
        }
    }

    public void deleteGroup(String groupId) {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try (PreparedStatement delStocks = conn.prepareStatement("DELETE FROM watchlist_stocks WHERE group_id = ?")) {
                delStocks.setString(1, groupId);
                delStocks.executeUpdate();
            }
            try (PreparedStatement delGroup = conn.prepareStatement("DELETE FROM watchlist_groups WHERE group_id = ?")) {
                delGroup.setString(1, groupId);
                delGroup.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete watchlist group " + groupId, e);
        }
    }

    // ---- stocks ----------------------------------------------------------

    public List<WatchlistStock> listStocks(String groupId) {
        String sql = "SELECT code, name FROM watchlist_stocks WHERE group_id = ? ORDER BY code ASC";
        List<WatchlistStock> out = new ArrayList<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new WatchlistStock(rs.getString("code"), rs.getString("name")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list watchlist stocks for " + groupId, e);
        }
        return out;
    }

    public Map<String, String> stockNames(String groupId) {
        Map<String, String> out = new LinkedHashMap<>();
        for (WatchlistStock s : listStocks(groupId)) {
            out.put(s.code(), s.name() != null ? s.name() : s.code());
        }
        return out;
    }

    public List<String> stockCodes(String groupId) {
        List<String> out = new ArrayList<>();
        for (WatchlistStock s : listStocks(groupId)) {
            out.add(s.code());
        }
        return out;
    }

    public void addStock(String groupId, String code, String name) {
        String sql = "INSERT OR REPLACE INTO watchlist_stocks(group_id, code, name) VALUES(?, ?, ?)";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupId);
            ps.setString(2, code);
            ps.setString(3, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to add stock " + code + " to " + groupId, e);
        }
    }

    public void removeStock(String groupId, String code) {
        String sql = "DELETE FROM watchlist_stocks WHERE group_id = ? AND code = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupId);
            ps.setString(2, code);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to remove stock " + code + " from " + groupId, e);
        }
    }

    public boolean hasStock(String groupId, String code) {
        String sql = "SELECT 1 FROM watchlist_stocks WHERE group_id = ? AND code = ?";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupId);
            ps.setString(2, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check stock existence", e);
        }
    }

    /** 把自选股 groupId 规范化成带 wl_ 前缀的 key。
     */
    public static String toGroupKey(String rawId) {
        if (rawId == null || rawId.isEmpty()) {
            return PREFIX + "default";
        }
        return rawId.startsWith(PREFIX) ? rawId : PREFIX + rawId;
    }

    /** 判断一个 market key 是否为自选股分组。
     */
    public static boolean isWatchlist(String market) {
        return market != null && market.startsWith(PREFIX);
    }
}
