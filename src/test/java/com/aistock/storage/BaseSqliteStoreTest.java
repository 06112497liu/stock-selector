package com.aistock.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseSqliteStoreTest {

    @TempDir
    Path tmp;

    private static final String CREATE_ITEMS = "CREATE TABLE IF NOT EXISTS items ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
            + "name TEXT NOT NULL, "
            + "value REAL)";

    private static class TestStore extends BaseSqliteStore {
        TestStore(String dbPath) {
            super(dbPath);
        }

        void initWithSchema() {
            initSchema(List.of(CREATE_ITEMS), "init failed");
        }

        Connection openConnection() throws Exception {
            return connect();
        }

        int insertItem(String name, double value) {
            return executeUpdate(
                    "INSERT INTO items(name, value) VALUES(?, ?)",
                    "insert failed",
                    ps -> {
                        ps.setString(1, name);
                        ps.setDouble(2, value);
                    });
        }

        long insertItemReturnKey(String name, double value) {
            return executeInsertReturnKey(
                    "INSERT INTO items(name, value) VALUES(?, ?)",
                    "insert failed",
                    ps -> {
                        ps.setString(1, name);
                        ps.setDouble(2, value);
                    });
        }

        int updateValue(String name, double newValue) {
            return executeUpdate(
                    "UPDATE items SET value = ? WHERE name = ?",
                    "update failed",
                    ps -> {
                        ps.setDouble(1, newValue);
                        ps.setString(2, name);
                    });
        }

        int deleteItem(String name) {
            return executeUpdate(
                    "DELETE FROM items WHERE name = ?",
                    "delete failed",
                    ps -> ps.setString(1, name));
        }

        List<String> listNames() {
            return executeQuery(
                    "SELECT name FROM items ORDER BY name ASC",
                    "query failed",
                    rs -> {
                        List<String> out = new ArrayList<>();
                        while (rs.next()) {
                            out.add(rs.getString("name"));
                        }
                        return out;
                    },
                    ps -> {});
        }

        Double findValue(String name) {
            return executeQuery(
                    "SELECT value FROM items WHERE name = ?",
                    "query failed",
                    rs -> rs.next() ? rs.getDouble("value") : null,
                    ps -> ps.setString(1, name));
        }

        int countItems() {
            return executeQuery(
                    "SELECT COUNT(*) AS c FROM items",
                    "count failed",
                    rs -> rs.next() ? rs.getInt("c") : 0,
                    ps -> {});
        }

        void batchInsert(List<String> names) {
            executeTransaction(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO items(name, value) VALUES(?, 0)")) {
                    for (String n : names) {
                        ps.setString(1, n);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }, "batch insert failed");
        }

        void transactionalInsertThenRollback(String name) {
            executeTransaction(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO items(name, value) VALUES(?, 0)")) {
                    ps.setString(1, name);
                    ps.executeUpdate();
                }
                throw new java.sql.SQLException("boom");
            }, "tx failed");
        }
    }

    private TestStore freshStore() {
        TestStore s = new TestStore(tmp.resolve("test.sqlite").toString());
        s.initWithSchema();
        return s;
    }

    @Test
    void nonExistentDbFile_autoCreatesAndInitsSchema() throws Exception {
        Path dbFile = tmp.resolve("brand_new.sqlite");
        assertFalse(Files.exists(dbFile), "测试前文件不应存在");

        TestStore s = new TestStore(dbFile.toString());
        s.initWithSchema();

        assertTrue(Files.exists(dbFile), "调用 initSchema 后应自动创建 db 文件");

        try (Connection conn = s.openConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='items'");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "items 表应已建好");
        }
    }

    @Test
    void connect_returnsValidConnection() throws Exception {
        TestStore s = freshStore();
        try (Connection conn = s.openConnection()) {
            assertNotNull(conn);
            assertFalse(conn.isClosed());
            assertTrue(conn.isValid(1));
        }
    }

    @Test
    void initSchema_idempotent_runsTwiceWithoutError() {
        TestStore s = new TestStore(tmp.resolve("twice.sqlite").toString());
        s.initWithSchema();
        s.initWithSchema();
        assertEquals(0, s.countItems());
    }

    @Test
    void executeUpdate_insert() {
        TestStore s = freshStore();
        int rows = s.insertItem("apple", 3.14);
        assertEquals(1, rows);
        assertEquals(1, s.countItems());
    }

    @Test
    void executeUpdate_update() {
        TestStore s = freshStore();
        s.insertItem("apple", 1.0);
        int rows = s.updateValue("apple", 9.99);
        assertEquals(1, rows);
        assertEquals(9.99, s.findValue("apple"), 1e-9);
    }

    @Test
    void executeUpdate_updateNonAffected_returnsZero() {
        TestStore s = freshStore();
        int rows = s.updateValue("nope", 1.0);
        assertEquals(0, rows);
    }

    @Test
    void executeUpdate_delete() {
        TestStore s = freshStore();
        s.insertItem("apple", 1.0);
        s.insertItem("banana", 2.0);
        int rows = s.deleteItem("apple");
        assertEquals(1, rows);
        assertEquals(List.of("banana"), s.listNames());
    }

    @Test
    void executeInsertReturnKey_returnsAutoIncrementId() {
        TestStore s = freshStore();
        long id1 = s.insertItemReturnKey("a", 1);
        long id2 = s.insertItemReturnKey("b", 2);
        assertTrue(id1 > 0);
        assertTrue(id2 > id1);
    }

    @Test
    void executeQuery_listMapping() {
        TestStore s = freshStore();
        s.insertItem("cherry", 1);
        s.insertItem("apple", 2);
        s.insertItem("banana", 3);
        assertEquals(List.of("apple", "banana", "cherry"), s.listNames());
    }

    @Test
    void executeQuery_singleResultWithParam() {
        TestStore s = freshStore();
        s.insertItem("x", 42.5);
        assertEquals(42.5, s.findValue("x"), 1e-9);
    }

    @Test
    void executeQuery_noMatch_returnsNull() {
        TestStore s = freshStore();
        assertEquals(null, s.findValue("nope"));
    }

    @Test
    void executeTransaction_commitsOnSuccess() {
        TestStore s = freshStore();
        s.batchInsert(List.of("a", "b", "c"));
        assertEquals(3, s.countItems());
        assertEquals(List.of("a", "b", "c"), s.listNames());
    }

    @Test
    void executeTransaction_rollsBackOnSqlException() {
        TestStore s = freshStore();
        s.insertItem("existing", 1.0);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> s.transactionalInsertThenRollback("should_rollback"));
        assertEquals("tx failed", ex.getMessage());

        assertEquals(1, s.countItems(), "异常应触发回滚,只保留原有数据");
        assertEquals(List.of("existing"), s.listNames());
    }

    @Test
    void executeUpdate_wrapsSqlException_inIllegalState() {
        TestStore s = freshStore();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> s.executeUpdate(
                        "INSERT INTO no_such_table(x) VALUES(1)",
                        "custom message",
                        ps -> {}));
        assertEquals("custom message", ex.getMessage());
        assertNotNull(ex.getCause());
        assertTrue(ex.getCause() instanceof java.sql.SQLException);
    }

    @Test
    void executeQuery_wrapsSqlException_inIllegalState() {
        TestStore s = freshStore();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> s.executeQuery(
                        "SELECT * FROM no_such_table",
                        "boom",
                        rs -> null,
                        ps -> {}));
        assertEquals("boom", ex.getMessage());
        assertNotNull(ex.getCause());
    }

    @Test
    void initSchema_wrapsSqlException_inIllegalState() {
        TestStore s = new TestStore(tmp.resolve("bad.sqlite").toString());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> s.initSchema(List.of("NOT VALID SQL"), "ddl failed"));
        assertEquals("ddl failed", ex.getMessage());
        assertNotNull(ex.getCause());
    }

    @Test
    void executeInsertReturnKey_wrapsSqlException_inIllegalState() {
        TestStore s = freshStore();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> s.executeInsertReturnKey(
                        "INSERT INTO no_such_table(x) VALUES(1)",
                        "bad insert",
                        ps -> {}));
        assertEquals("bad insert", ex.getMessage());
        assertNotNull(ex.getCause());
    }

    @Test
    void persistsAcrossInstances() {
        Path dbFile = tmp.resolve("persist.sqlite");
        TestStore s1 = new TestStore(dbFile.toString());
        s1.initWithSchema();
        s1.insertItem("hello", 123.0);

        TestStore s2 = new TestStore(dbFile.toString());
        s2.initWithSchema();
        assertEquals(123.0, s2.findValue("hello"), 1e-9);
    }
}
