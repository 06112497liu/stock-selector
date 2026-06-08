package com.aistock.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

abstract class BaseSqliteStore {

    @FunctionalInterface
    protected interface SqlConsumer<T> {
        void accept(T t) throws SQLException;
    }

    @FunctionalInterface
    protected interface SqlFunction<T, R> {
        R apply(T t) throws SQLException;
    }

    protected final String url;

    protected BaseSqliteStore(String dbPath) {
        this.url = "jdbc:sqlite:" + dbPath;
    }

    protected Connection connect() throws SQLException {
        return DriverManager.getConnection(url);
    }

    protected void initSchema(List<String> ddls, String errorMessage) {
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            for (String ddl : ddls) {
                st.execute(ddl);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(errorMessage, e);
        }
    }

    protected int executeUpdate(String sql, String errorMessage, SqlConsumer<PreparedStatement> binder) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.accept(ps);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(errorMessage, e);
        }
    }

    protected long executeInsertReturnKey(String sql, String errorMessage, SqlConsumer<PreparedStatement> binder) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            binder.accept(ps);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(errorMessage, e);
        }
        return -1;
    }

    protected <T> T executeQuery(String sql,
                                 String errorMessage,
                                 SqlFunction<ResultSet, T> mapper,
                                 SqlConsumer<PreparedStatement> binder) {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.accept(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return mapper.apply(rs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(errorMessage, e);
        }
    }

    protected void executeTransaction(SqlConsumer<Connection> work, String errorMessage) {
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try {
                work.accept(conn);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(errorMessage, e);
        }
    }
}
