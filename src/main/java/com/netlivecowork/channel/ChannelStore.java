package com.netlivecowork.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Single-owner delivery state. Never connects to the session backend's database. */
@Component
public class ChannelStore {
    private final ObjectMapper json;
    private final Connection db;
    private final FileChannel lockFile;
    private final FileLock lease;

    public ChannelStore(ChannelConfig config, ObjectMapper json) throws Exception {
        this.json = json;
        Path path = config.dataFile().toAbsolutePath();
        Files.createDirectories(path.getParent());
        lockFile = FileChannel.open(Path.of(path + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lease = lockFile.tryLock();
        if (lease == null) { lockFile.close(); throw new IllegalStateException("Another Channel instance owns this database"); }
        db = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (var statement = db.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("CREATE TABLE IF NOT EXISTS channel_records (kind TEXT NOT NULL, id TEXT NOT NULL, body TEXT NOT NULL, PRIMARY KEY(kind,id))");
        }
        // 派发或发送中崩溃无法证明外部是否已受理；保留 unknown，绝不自动重发。
        for (String kind : List.of("inbox", "outbox")) for (var row : all(kind)) {
            if (List.of("dispatching", "sending").contains(row.path("state").asText())) {
                row.put("state", "unknown"); put(kind, row.path("id").asText(), row);
            }
        }
    }
    public synchronized ObjectNode get(String kind, String id) {
        try (var query = db.prepareStatement("SELECT body FROM channel_records WHERE kind=? AND id=?")) {
            query.setString(1, kind); query.setString(2, id);
            try (var rows = query.executeQuery()) { return rows.next() ? (ObjectNode) json.readTree(rows.getString(1)) : null; }
        } catch (Exception e) { throw new IllegalStateException("Cannot read Channel state", e); }
    }
    public synchronized List<ObjectNode> all(String kind) {
        var out = new ArrayList<ObjectNode>();
        try (var query = db.prepareStatement("SELECT body FROM channel_records WHERE kind=? ORDER BY rowid")) {
            query.setString(1, kind);
            try (var rows = query.executeQuery()) { while (rows.next()) out.add((ObjectNode) json.readTree(rows.getString(1))); }
            return out;
        } catch (Exception e) { throw new IllegalStateException("Cannot list Channel state", e); }
    }
    public synchronized void put(String kind, String id, JsonNode body) {
        try (var query = db.prepareStatement("INSERT INTO channel_records(kind,id,body) VALUES(?,?,?) ON CONFLICT(kind,id) DO UPDATE SET body=excluded.body")) {
            query.setString(1, kind); query.setString(2, id); query.setString(3, json.writeValueAsString(body)); query.executeUpdate();
        } catch (Exception e) { throw new IllegalStateException("Cannot save Channel state", e); }
    }
    public synchronized void atomic(Runnable change) {
        try {
            db.setAutoCommit(false);
            try { change.run(); db.commit(); }
            catch (RuntimeException | Error e) { db.rollback(); throw e; }
            finally { db.setAutoCommit(true); }
        } catch (java.sql.SQLException e) { throw new IllegalStateException("Cannot commit Channel state", e); }
    }
    @PreDestroy public synchronized void close() throws Exception {
        db.close(); lease.release(); lockFile.close();
    }
}
