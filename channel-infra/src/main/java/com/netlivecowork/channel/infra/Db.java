package com.netlivecowork.channel.infra;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单文件 SQLite 事务设施。写路径串行化以规避 SQLite 单写者限制；
 * 网络调用一律在事务之外执行（INV-20），仓储只做本地原子提交。
 */
public final class Db implements AutoCloseable {

    private final String url;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ThreadLocal<Connection> current = new ThreadLocal<>();
    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
    /** 内存数据库需要一个常驻连接，否则最后一个连接关闭时库会被销毁。 */
    private Connection anchor;

    private Db(String url) {
        this.url = url;
    }

    /** 连接参数：WAL 允许读并发，busy_timeout 缓解短暂写锁竞争。 */
    private static final String PRAGMAS = "busy_timeout=10000&journal_mode=WAL&foreign_keys=on&synchronous=FULL";

    /** 文件数据库；父目录会被创建。 */
    public static Db file(java.nio.file.Path path) {
        try {
            java.nio.file.Path absolute = path.toAbsolutePath();
            if (absolute.getParent() != null) {
                java.nio.file.Files.createDirectories(absolute.getParent());
            }
            return new Db("jdbc:sqlite:" + absolute + "?" + PRAGMAS);
        } catch (java.io.IOException e) {
            throw new DbException("cannot create database directory", e);
        }
    }

    /** 内存数据库，仅用于测试或演示。 */
    public static Db memory() {
        Db db = new Db("jdbc:sqlite:file:mem-" + Ids.random(12) + "?mode=memory&cache=shared&" + PRAGMAS);
        db.anchor = db.open();
        return db;
    }

    @Override
    public void close() {
        Connection connection = anchor;
        anchor = null;
        if (connection != null) {
            close(connection);
        }
    }

    public String url() {
        return url;
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    /** 在事务内执行。可嵌套：内层复用同一事务，最外层提交或回滚。 */
    public <T> T tx(SqlWork<T> work) {
        return inWriteTx(work);
    }

    public boolean inTransaction() {
        return current.get() != null;
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        Connection connection = current.get();
        boolean owned = connection == null;
        try {
            if (connection == null) {
                connection = open();
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, params);
                try (ResultSet rs = statement.executeQuery()) {
                    List<T> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(mapper.map(rs));
                    }
                    return rows;
                }
            }
        } catch (SQLException e) {
            throw new DbException("query failed", e);
        } finally {
            if (owned && connection != null) {
                close(connection);
            }
        }
    }

    /** 单行查询；无结果返回 null。 */
    public <T> T queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> rows = query(sql, mapper, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 执行写入（insert/update/delete），返回影响行数。 */
    public int update(String sql, Object... params) {
        return inWriteTx(connection -> updateOn(connection, sql, params));
    }

    /** 插入并返回自增主键。 */
    public long insert(String sql, Object... params) {
        return inWriteTx(connection -> insertOn(connection, sql, params));
    }

    /** 幂等 DDL：全部使用 IF NOT EXISTS，可在每次启动执行。 */
    public void ddl(String... statements) {
        tx(connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.execute(sql);
                }
            }
            return null;
        });
    }

    private <T> T inWriteTx(SqlWork<T> work) {
        if (depth.get() > 0) {
            depth.set(depth.get() + 1);
            try {
                return work.apply(current.get());
            } catch (SQLException e) {
                throw new DbException("nested write failed", e);
            } finally {
                depth.set(depth.get() - 1);
            }
        }
        writeLock.lock();
        Connection connection = null;
        try {
            connection = open();
            try {
                connection.setAutoCommit(false);
            } catch (SQLException e) {
                throw new DbException("cannot start transaction", e);
            }
            current.set(connection);
            depth.set(1);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException e) {
                rollback(connection);
                throw new DbException("write failed", e);
            } catch (RuntimeException e) {
                rollback(connection);
                throw e;
            } finally {
                depth.set(0);
                current.remove();
            }
        } finally {
            if (connection != null) {
                close(connection);
            }
            writeLock.unlock();
        }
    }

    private int updateOn(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            return statement.executeUpdate();
        }
    }

    private long insertOn(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, params);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        }
    }

    private void bind(PreparedStatement statement, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object value = params[i];
            int index = i + 1;
            if (value == null) {
                statement.setNull(index, java.sql.Types.NULL);
            } else if (value instanceof String text) {
                statement.setString(index, text);
            } else if (value instanceof Long || value instanceof Integer || value instanceof Short) {
                statement.setLong(index, ((Number) value).longValue());
            } else if (value instanceof Boolean flag) {
                statement.setInt(index, flag ? 1 : 0);
            } else if (value instanceof byte[] bytes) {
                statement.setBytes(index, bytes);
            } else if (value instanceof Instant instant) {
                statement.setString(index, instant.toString());
            } else if (value instanceof Enum<?> enumValue) {
                statement.setString(index, enumValue.name());
            } else {
                statement.setString(index, value.toString());
            }
        }
    }

    private Connection open() {
        try {
            return DriverManager.getConnection(url);
        } catch (SQLException e) {
            throw new DbException("cannot open database connection", e);
        }
    }

    private void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 连接已坏时无事务可回滚；错误由上层感知。
        }
    }

    private void close(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 关闭失败不改变业务结论。
        }
    }

    /** 便捷读取：NULL 安全的字符串列。 */
    public static String str(ResultSet rs, String column) throws SQLException {
        return rs.getString(column);
    }

    /** 便捷读取：NULL 安全的长整型列。 */
    public static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /** 便捷读取：布尔列（0/1）。 */
    public static boolean bool(ResultSet rs, String column) throws SQLException {
        return rs.getInt(column) != 0;
    }

    /** 便捷读取：可空时间列。 */
    public static Instant instant(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
