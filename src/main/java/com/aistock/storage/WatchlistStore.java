package com.aistock.storage;

import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WatchlistStore extends BaseSqliteStore {

    public record WatchlistGroup(String groupId, String groupName, String marketType, LocalDateTime createdAt) {
    }

    public record WatchlistStock(String code, String name) {
    }

    public static final String PREFIX = "wl_";

    public WatchlistStore(String dbPath) {
        super(dbPath);
        initSchema();
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
        initSchema(List.of(groupDdl, stockDdl), "Failed to initialise watchlist schema");
    }

    private static String nowIso() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    public List<WatchlistGroup> listGroups() {
        String sql = "SELECT group_id, group_name, market_type, created_at FROM watchlist_groups ORDER BY created_at ASC";
        return executeQuery(sql, "Failed to list watchlist groups", rs -> {
            List<WatchlistGroup> out = new ArrayList<>();
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
            return out;
        }, ps -> {});
    }

    public WatchlistGroup getGroup(String groupId) {
        String sql = "SELECT group_id, group_name, market_type, created_at FROM watchlist_groups WHERE group_id = ?";
        return executeQuery(sql, "Failed to get watchlist group", rs -> {
            if (rs.next()) {
                return new WatchlistGroup(
                        rs.getString("group_id"),
                        rs.getString("group_name"),
                        rs.getString("market_type"),
                        rs.getString("created_at") != null
                                ? LocalDateTime.parse(rs.getString("created_at"), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                : null);
            }
            return null;
        }, ps -> ps.setString(1, groupId));
    }

    public void createGroup(String groupId, String groupName, String marketType) {
        String sql = "INSERT INTO watchlist_groups(group_id, group_name, market_type, created_at) VALUES(?, ?, ?, ?)";
        executeUpdate(sql, "Failed to create watchlist group " + groupId, ps -> {
            ps.setString(1, groupId);
            ps.setString(2, groupName);
            ps.setString(3, marketType);
            ps.setString(4, nowIso());
        });
    }

    public void renameGroup(String groupId, String newName) {
        String sql = "UPDATE watchlist_groups SET group_name = ? WHERE group_id = ?";
        executeUpdate(sql, "Failed to rename watchlist group " + groupId, ps -> {
            ps.setString(1, newName);
            ps.setString(2, groupId);
        });
    }

    public void deleteGroup(String groupId) {
        executeTransaction(conn -> {
            try (PreparedStatement delStocks = conn.prepareStatement("DELETE FROM watchlist_stocks WHERE group_id = ?")) {
                delStocks.setString(1, groupId);
                delStocks.executeUpdate();
            }
            try (PreparedStatement delGroup = conn.prepareStatement("DELETE FROM watchlist_groups WHERE group_id = ?")) {
                delGroup.setString(1, groupId);
                delGroup.executeUpdate();
            }
        }, "Failed to delete watchlist group " + groupId);
    }

    public List<WatchlistStock> listStocks(String groupId) {
        String sql = "SELECT code, name FROM watchlist_stocks WHERE group_id = ? ORDER BY code ASC";
        return executeQuery(sql, "Failed to list watchlist stocks for " + groupId, rs -> {
            List<WatchlistStock> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new WatchlistStock(rs.getString("code"), rs.getString("name")));
            }
            return out;
        }, ps -> ps.setString(1, groupId));
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
        executeUpdate(sql, "Failed to add stock " + code + " to " + groupId, ps -> {
            ps.setString(1, groupId);
            ps.setString(2, code);
            ps.setString(3, name);
        });
    }

    public void removeStock(String groupId, String code) {
        String sql = "DELETE FROM watchlist_stocks WHERE group_id = ? AND code = ?";
        executeUpdate(sql, "Failed to remove stock " + code + " from " + groupId, ps -> {
            ps.setString(1, groupId);
            ps.setString(2, code);
        });
    }

    public boolean hasStock(String groupId, String code) {
        String sql = "SELECT 1 FROM watchlist_stocks WHERE group_id = ? AND code = ?";
        return executeQuery(sql, "Failed to check stock existence", rs -> rs.next(), ps -> {
            ps.setString(1, groupId);
            ps.setString(2, code);
        });
    }

    public static String toGroupKey(String rawId) {
        if (rawId == null || rawId.isEmpty()) {
            return PREFIX + "default";
        }
        return rawId.startsWith(PREFIX) ? rawId : PREFIX + rawId;
    }

    public static boolean isWatchlist(String market) {
        return market != null && market.startsWith(PREFIX);
    }
}
