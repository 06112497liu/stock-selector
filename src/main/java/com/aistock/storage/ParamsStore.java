package com.aistock.storage;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ParamsStore extends BaseSqliteStore {

    private static final String KEY_TOP_N = "topN";
    private static final String KEY_STOP_LOSS = "stopLossPct";

    public ParamsStore(String dbPath) {
        super(dbPath);
        initSchema();
    }

    private void initSchema() {
        String kvDdl = "CREATE TABLE IF NOT EXISTS params_kv ("
                + "k TEXT PRIMARY KEY, "
                + "v REAL)";
        String wDdl = "CREATE TABLE IF NOT EXISTS factor_weights ("
                + "factor TEXT PRIMARY KEY, "
                + "weight REAL)";
        initSchema(List.of(kvDdl, wDdl), "Failed to initialise params schema");
    }

    public StrategyParams load() {
        StrategyParams def = StrategyParams.defaults();
        Map<String, Double> kv = readKv();
        Map<String, Double> weights = readWeights();

        int topN = kv.containsKey(KEY_TOP_N) ? (int) Math.round(kv.get(KEY_TOP_N)) : def.topN();
        double stopLoss = kv.containsKey(KEY_STOP_LOSS) ? kv.get(KEY_STOP_LOSS) : def.stopLossPct();
        Map<String, Double> w = weights.isEmpty() ? def.factorWeights() : weights;
        return new StrategyParams(topN, stopLoss, w);
    }

    public void save(StrategyParams params) {
        executeTransaction(conn -> {
            try (Statement clear = conn.createStatement()) {
                clear.executeUpdate("DELETE FROM params_kv");
                clear.executeUpdate("DELETE FROM factor_weights");
            }
            String kvSql = "INSERT INTO params_kv(k, v) VALUES(?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(kvSql)) {
                ps.setString(1, KEY_TOP_N);
                ps.setDouble(2, params.topN());
                ps.addBatch();
                ps.setString(1, KEY_STOP_LOSS);
                ps.setDouble(2, params.stopLossPct());
                ps.addBatch();
                ps.executeBatch();
            }
            String wSql = "INSERT INTO factor_weights(factor, weight) VALUES(?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(wSql)) {
                for (Map.Entry<String, Double> e : params.factorWeights().entrySet()) {
                    ps.setString(1, e.getKey());
                    ps.setDouble(2, e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }, "Failed to save strategy params");
    }

    public void reset() {
        executeTransaction(conn -> {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DELETE FROM params_kv");
                st.executeUpdate("DELETE FROM factor_weights");
            }
        }, "Failed to reset strategy params");
    }

    private Map<String, Double> readKv() {
        return executeQuery("SELECT k, v FROM params_kv", "Failed to read params_kv", rs -> {
            Map<String, Double> out = new LinkedHashMap<>();
            while (rs.next()) {
                out.put(rs.getString("k"), rs.getDouble("v"));
            }
            return out;
        }, ps -> {});
    }

    private Map<String, Double> readWeights() {
        return executeQuery("SELECT factor, weight FROM factor_weights ORDER BY factor ASC",
                "Failed to read factor_weights", rs -> {
                    Map<String, Double> out = new LinkedHashMap<>();
                    while (rs.next()) {
                        out.put(rs.getString("factor"), rs.getDouble("weight"));
                    }
                    return out;
                }, ps -> {});
    }
}
