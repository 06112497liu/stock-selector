package com.aistock.storage;

import com.aistock.storage.Store.NavPoint;
import com.aistock.storage.Store.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Store} CRUD 单测:tmp 目录 SQLite,不联网。 */
class StoreTest {

    @TempDir
    Path tmp;

    private Store store;

    @BeforeEach
    void setUp() {
        store = new Store(tmp.resolve("us_ledger.sqlite").toString());
    }

    @Test
    void emptyDb_defaults() {
        assertEquals(0.0, store.getCash(), 1e-9, "空库现金默认 0");
        assertTrue(store.getPositions().isEmpty(), "空库持仓为空 map");
        assertTrue(store.navHistory().isEmpty(), "空库净值历史为空");
    }

    @Test
    void positions_crud() {
        store.upsertPosition("AAPL", 10, 180.0);
        store.upsertPosition("MSFT", 5, 400.0);

        Map<String, Position> pos = store.getPositions();
        assertEquals(2, pos.size());
        assertEquals(10, pos.get("AAPL").shares(), 1e-9);
        assertEquals(180.0, pos.get("AAPL").avgCost(), 1e-9);

        // upsert 同 code 覆盖
        store.upsertPosition("AAPL", 20, 190.0);
        assertEquals(20, store.getPositions().get("AAPL").shares(), 1e-9);
        assertEquals(190.0, store.getPositions().get("AAPL").avgCost(), 1e-9);

        store.removePosition("MSFT");
        assertFalse(store.getPositions().containsKey("MSFT"));
        assertEquals(1, store.getPositions().size());
    }

    @Test
    void cash_setGet() {
        store.setCash(12345.67);
        assertEquals(12345.67, store.getCash(), 1e-9);
        store.setCash(0.0);
        assertEquals(0.0, store.getCash(), 1e-9);
    }

    @Test
    void replaceAllPositions_clearsThenWrites() {
        store.upsertPosition("OLD1", 1, 1);
        store.upsertPosition("OLD2", 2, 2);

        Map<String, Position> truth = new LinkedHashMap<>();
        truth.put("NEW1", new Position(7, 70));
        truth.put("NEW2", new Position(8, 80));
        store.replaceAllPositions(truth);

        Map<String, Position> pos = store.getPositions();
        assertEquals(2, pos.size());
        assertFalse(pos.containsKey("OLD1"), "旧持仓应被清空");
        assertFalse(pos.containsKey("OLD2"), "旧持仓应被清空");
        assertEquals(7, pos.get("NEW1").shares(), 1e-9);

        // 用空 map 替换 -> 清空
        store.replaceAllPositions(Map.of());
        assertTrue(store.getPositions().isEmpty(), "空 map 替换应清空持仓");
    }

    @Test
    void recordNav_upsertSameDay_noDuplicate_andAscending() {
        store.recordNav("2024-01-03", 100.0);
        store.recordNav("2024-01-01", 90.0);
        store.recordNav("2024-01-02", 95.0);
        // 同一天再记 -> upsert 覆盖,不重复
        store.recordNav("2024-01-02", 96.0);

        List<NavPoint> nav = store.navHistory();
        assertEquals(3, nav.size(), "同一天 upsert 不应产生重复行");
        // 升序
        assertEquals("2024-01-01", nav.get(0).date());
        assertEquals("2024-01-02", nav.get(1).date());
        assertEquals("2024-01-03", nav.get(2).date());
        assertEquals(96.0, nav.get(1).nav(), 1e-9, "同日第二次记录应覆盖前值");
    }

    @Test
    void clearNav_empties() {
        store.recordNav("2024-01-01", 100.0);
        store.recordNav("2024-01-02", 101.0);
        assertEquals(2, store.navHistory().size());
        store.clearNav();
        assertTrue(store.navHistory().isEmpty(), "clearNav 后净值历史应为空");
    }

    @Test
    void persistsAcrossInstances() {
        store.upsertPosition("AAPL", 3, 100);
        store.setCash(500);
        store.recordNav("2024-01-01", 800);

        // 新实例指向同一文件,数据应仍在(Docker 重启不丢的本质)
        Store reopened = new Store(tmp.resolve("us_ledger.sqlite").toString());
        assertEquals(3, reopened.getPositions().get("AAPL").shares(), 1e-9);
        assertEquals(500, reopened.getCash(), 1e-9);
        assertEquals(1, reopened.navHistory().size());
    }
}
