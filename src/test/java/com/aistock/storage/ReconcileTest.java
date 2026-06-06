package com.aistock.storage;

import com.aistock.storage.Store.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Reconcile} 单测:替换持仓 + 设现金 + 清空净值历史(关键教训)。 */
class ReconcileTest {

    @TempDir
    Path tmp;

    private Store store;

    @BeforeEach
    void setUp() {
        store = new Store(tmp.resolve("cn_ledger.sqlite").toString());
    }

    @Test
    void applyReconciliation_replacesPositions_setsCash_andClearsNav() {
        // 旧账本:有旧持仓、旧现金、且已有几条净值(模拟旧的虚假峰值)。
        store.upsertPosition("OLD", 99, 9.9);
        store.setCash(111.0);
        store.recordNav("2024-01-01", 1000.0);
        store.recordNav("2024-01-02", 1500.0); // 虚假峰值
        store.recordNav("2024-01-03", 1200.0);
        assertEquals(3, store.navHistory().size(), "前置:已有净值历史");

        Map<String, Position> truth = new LinkedHashMap<>();
        truth.put("600519", new Position(2, 1600));
        truth.put("000001", new Position(100, 12));

        Reconcile.applyReconciliation(store, truth, 5000.0);

        // 持仓被整体替换
        Map<String, Position> pos = store.getPositions();
        assertEquals(2, pos.size());
        assertFalse(pos.containsKey("OLD"), "旧持仓应被替换掉");
        assertEquals(1600, pos.get("600519").avgCost(), 1e-9);

        // 现金被设置
        assertEquals(5000.0, store.getCash(), 1e-9);

        // 关键:净值历史被清空,旧虚假峰值不再污染后续回撤判断/曲线基准
        assertTrue(store.navHistory().isEmpty(),
                "对账后净值历史必须清空,以对账后净值为新基准");
    }

    @Test
    void netValue_usesPriceWhenAvailable_elseAvgCost() {
        Map<String, Position> pos = new LinkedHashMap<>();
        pos.put("AAPL", new Position(10, 100));   // 有现价 120
        pos.put("MSFT", new Position(5, 400));    // 缺现价 -> 退回成本 400

        Map<String, Double> px = new LinkedHashMap<>();
        px.put("AAPL", 120.0);
        // MSFT 缺价

        double nav = Reconcile.netValue(1000.0, pos, px);
        // 1000 + 10*120 + 5*400 = 1000 + 1200 + 2000 = 4200
        assertEquals(4200.0, nav, 1e-9);
    }

    @Test
    void netValue_nanPrice_fallsBackToCost() {
        Map<String, Position> pos = new LinkedHashMap<>();
        pos.put("X", new Position(2, 50));
        Map<String, Double> px = new LinkedHashMap<>();
        px.put("X", Double.NaN);
        double nav = Reconcile.netValue(0.0, pos, px);
        assertEquals(100.0, nav, 1e-9, "NaN 现价应退回成本计,不崩、不出 NaN");
    }
}
