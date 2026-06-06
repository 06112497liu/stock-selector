package com.aistock.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 选股策略参数存储(SQLite,按 market 隔离)。
 *
 * <p>每个 market(us/cn)一个独立 db 文件,放在 {@code market.cache-dir} 目录下
 * (如 {@code {cacheDir}/{market}_params.sqlite}),与账本 {@link Store} 同处,
 * 保证 Docker 卷 /data 持久化、容器重启参数不丢。<b>独立于账本 db</b>,
 * 不污染现有账本表 / 账本测试。
 *
 * <p>表结构(单值与权重分两表,权重存成多行 key-value):
 * <pre>
 * params_kv(k TEXT PRIMARY KEY, v REAL)        -- topN / stopLossPct 单值
 * factor_weights(factor TEXT PRIMARY KEY, weight REAL)
 * </pre>
 *
 * <p>无记录时 {@link #load()} 返回 {@link StrategyParams#defaults()};
 * {@link #reset()} 删全部记录回默认。每操作一个短生命周期 JDBC 连接
 * (同 {@link Store} 写法)。
 */
public final class ParamsStore {

    private static final String KEY_TOP_N = "topN";
    private static final String KEY_STOP_LOSS = "stopLossPct";

    private final String url;

    /**
     * @param dbPath 参数 SQLite 文件路径(首次使用时自动创建)
     */
    public ParamsStore(String dbPath) {
        this.url = "jdbc:sqlite:" + dbPath;
        initSchema();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url);
    }

    private void initSchema() {
        String kvDdl = "CREATE TABLE IF NOT EXISTS params_kv ("
                + "k TEXT PRIMARY KEY, "
                + "v REAL)";
        String wDdl = "CREATE TABLE IF NOT EXISTS factor_weights ("
                + "factor TEXT PRIMARY KEY, "
                + "weight REAL)";
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute(kvDdl);
            st.execute(wDdl);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialise params schema", e);
        }
    }

    /**
     * 读取当前参数;<b>无完整记录则回退默认</b>。
     *
     * <p>口径:topN / stopLossPct 任一缺失或权重表为空,则用 {@link StrategyParams#defaults()}
     * 的对应部分兜底(保证页面始终有可用初值)。
     */
    public StrategyParams load() {
        StrategyParams def = StrategyParams.defaults();
        Map<String, Double> kv = readKv();
        Map<String, Double> weights = readWeights();

        int topN = kv.containsKey(KEY_TOP_N) ? (int) Math.round(kv.get(KEY_TOP_N)) : def.topN();
        double stopLoss = kv.containsKey(KEY_STOP_LOSS) ? kv.get(KEY_STOP_LOSS) : def.stopLossPct();
        Map<String, Double> w = weights.isEmpty() ? def.factorWeights() : weights;
        return new StrategyParams(topN, stopLoss, w);
    }

    /** 保存参数(整体替换:先清两表再写)。 */
    public void save(StrategyParams params) {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
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
            conn.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save strategy params", e);
        }
    }

    /** 重置:删全部记录,下次 {@link #load()} 回默认。 */
    public void reset() {
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM params_kv");
            st.executeUpdate("DELETE FROM factor_weights");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to reset strategy params", e);
        }
    }

    private Map<String, Double> readKv() {
        Map<String, Double> out = new LinkedHashMap<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("SELECT k, v FROM params_kv");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString("k"), rs.getDouble("v"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read params_kv", e);
        }
        return out;
    }

    private Map<String, Double> readWeights() {
        Map<String, Double> out = new LinkedHashMap<>();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT factor, weight FROM factor_weights ORDER BY factor ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString("factor"), rs.getDouble("weight"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read factor_weights", e);
        }
        return out;
    }
}
