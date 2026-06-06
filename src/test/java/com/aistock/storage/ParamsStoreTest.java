package com.aistock.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 策略参数存储往返 / 默认 / 重置 / 多因子权重正确性。tmp SQLite,不联网。
 */
class ParamsStoreTest {

    private static ParamsStore store(Path dir) {
        return new ParamsStore(dir.resolve("us_params.sqlite").toString());
    }

    @Test
    void emptyDb_returnsDefaults(@TempDir Path dir) {
        StrategyParams p = store(dir).load();
        StrategyParams def = StrategyParams.defaults();
        assertEquals(def.topN(), p.topN());
        assertEquals(def.stopLossPct(), p.stopLossPct(), 1e-12);
        assertEquals(def.factorWeights(), p.factorWeights());
    }

    @Test
    void saveLoad_roundTrip_withMultipleWeights(@TempDir Path dir) {
        ParamsStore s = store(dir);
        Map<String, Double> w = new LinkedHashMap<>();
        w.put("mom_20", -1.0);
        w.put("reversal_5", 1.5);
        w.put("vol_20", 0.3);
        s.save(new StrategyParams(3, -0.12, w));

        StrategyParams p = s.load();
        assertEquals(3, p.topN());
        assertEquals(-0.12, p.stopLossPct(), 1e-12);
        assertEquals(3, p.factorWeights().size());
        assertEquals(-1.0, p.factorWeights().get("mom_20"), 1e-12);
        assertEquals(1.5, p.factorWeights().get("reversal_5"), 1e-12);
        assertEquals(0.3, p.factorWeights().get("vol_20"), 1e-12);
    }

    @Test
    void reset_returnsToDefaults(@TempDir Path dir) {
        ParamsStore s = store(dir);
        s.save(new StrategyParams(9, -0.2, Map.of("mom_20", 2.0)));
        assertEquals(9, s.load().topN());

        s.reset();
        StrategyParams p = s.load();
        StrategyParams def = StrategyParams.defaults();
        assertEquals(def.topN(), p.topN());
        assertEquals(def.stopLossPct(), p.stopLossPct(), 1e-12);
        assertEquals(def.factorWeights(), p.factorWeights());
    }

    @Test
    void save_persistsAcrossNewStoreInstances(@TempDir Path dir) {
        String path = dir.resolve("us_params.sqlite").toString();
        new ParamsStore(path).save(new StrategyParams(7, -0.05, Map.of("mom_20", 0.9)));

        StrategyParams p = new ParamsStore(path).load();
        assertEquals(7, p.topN());
        assertTrue(p.factorWeights().containsKey("mom_20"));
        assertEquals(0.9, p.factorWeights().get("mom_20"), 1e-12);
    }
}
