package com.netlivecowork.channel.l2.store;

import com.netlivecowork.channel.infra.Db;
import java.util.List;

/** Receiver ledger survives host replacement; expired bodies retain custody tombstones. */
public final class InboxRepository {
    private final Db db;
    public InboxRepository(Db db) {
        this.db = db;
        db.ddl("""
            CREATE TABLE IF NOT EXISTS l2_delivery_inbox(
              seq INTEGER PRIMARY KEY AUTOINCREMENT, mailbox TEXT NOT NULL, delivery_id TEXT NOT NULL UNIQUE,
              receipt_ref TEXT NOT NULL, body TEXT, accepted_at TEXT NOT NULL, expires_at TEXT NOT NULL)
            """, "CREATE INDEX IF NOT EXISTS l2_inbox_mail ON l2_delivery_inbox(mailbox,seq)", """
            CREATE TABLE IF NOT EXISTS l2_inbox_consumers(
              ref TEXT PRIMARY KEY, mailbox TEXT NOT NULL, client_key TEXT NOT NULL, received INTEGER NOT NULL DEFAULT 0,
              baseline INTEGER NOT NULL DEFAULT 0, UNIQUE(mailbox,client_key))
            """, """
            CREATE TABLE IF NOT EXISTS l2_inbox_cursors(
              token TEXT PRIMARY KEY, consumer TEXT NOT NULL, start_seq INTEGER NOT NULL, end_seq INTEGER NOT NULL,
              baseline INTEGER NOT NULL, resync INTEGER NOT NULL, expires_at TEXT NOT NULL)
            """, """
            CREATE TABLE IF NOT EXISTS l2_inbox_issued(
              consumer TEXT NOT NULL, delivery_id TEXT NOT NULL, PRIMARY KEY(consumer,delivery_id))
            """, """
            CREATE TABLE IF NOT EXISTS l2_inbox_reads(
              mailbox TEXT NOT NULL, delivery_id TEXT NOT NULL, read_at TEXT NOT NULL,
              PRIMARY KEY(mailbox,delivery_id))
            """, """
            CREATE TABLE IF NOT EXISTS l2_inbox_gaps(
              consumer TEXT NOT NULL, old_position INTEGER NOT NULL, new_baseline INTEGER NOT NULL, recorded_at TEXT NOT NULL)
            """);
    }
    public record Item(long seq, String mailbox, String delivery, String receipt, String body, String expiry) {}
    public record Consumer(String ref, String mailbox, long received, long baseline) {}
    public record Cursor(String consumer, long start, long end, long baseline, boolean resync, String expiry) {}
    private Item map(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Item(r.getLong("seq"),r.getString("mailbox"),r.getString("delivery_id"),r.getString("receipt_ref"),r.getString("body"),r.getString("expires_at"));
    }
    public Item find(String delivery) { return db.queryOne("SELECT * FROM l2_delivery_inbox WHERE delivery_id=?",this::map,delivery); }
    public void append(String mailbox,String delivery,String receipt,String body,String now,String expiry) {
        db.update("INSERT INTO l2_delivery_inbox(mailbox,delivery_id,receipt_ref,body,accepted_at,expires_at) VALUES(?,?,?,?,?,?)",mailbox,delivery,receipt,body,now,expiry);
    }
    public List<Item> items(String mailbox,long after,int limit) {
        return db.query("SELECT * FROM l2_delivery_inbox WHERE mailbox=? AND seq>? ORDER BY seq LIMIT ?",this::map,mailbox,after,limit);
    }
    public long expiredThrough(String mailbox,String now) {
        Long n=db.queryOne("SELECT MAX(seq) FROM l2_delivery_inbox WHERE mailbox=? AND (body IS NULL OR expires_at<=?)",r->r.getLong(1),mailbox,now);
        return n==null?0:n;
    }
    public long count(String mailbox) { return db.queryOne("SELECT COUNT(*) FROM l2_delivery_inbox WHERE mailbox=? AND body IS NOT NULL",r->r.getLong(1),mailbox); }
    public void expire(String now) { db.update("UPDATE l2_delivery_inbox SET body=NULL WHERE expires_at<=? AND body IS NOT NULL",now); }
    public Consumer consumer(String mailbox,String key,String ref) {
        db.update("INSERT OR IGNORE INTO l2_inbox_consumers(ref,mailbox,client_key) VALUES(?,?,?)",ref,mailbox,key);
        return db.queryOne("SELECT * FROM l2_inbox_consumers WHERE mailbox=? AND client_key=?",this::consumer,mailbox,key);
    }
    private Consumer consumer(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Consumer(r.getString("ref"),r.getString("mailbox"),r.getLong("received"),r.getLong("baseline"));
    }
    public Consumer consumer(String ref) { return db.queryOne("SELECT * FROM l2_inbox_consumers WHERE ref=?",this::consumer,ref); }
    public Cursor cursor(String token) { return db.queryOne("SELECT * FROM l2_inbox_cursors WHERE token=?",r->new Cursor(r.getString("consumer"),r.getLong("start_seq"),r.getLong("end_seq"),r.getLong("baseline"),r.getInt("resync")!=0,r.getString("expires_at")),token); }
    public void cursor(String token,Cursor c) { db.update("INSERT INTO l2_inbox_cursors VALUES(?,?,?,?,?,?,?)",token,c.consumer(),c.start(),c.end(),c.baseline(),c.resync()?1:0,c.expiry()); }
    public void received(Consumer c,long position) { db.update("UPDATE l2_inbox_consumers SET received=MAX(received,?) WHERE ref=?",position,c.ref()); }
    public void baseline(Consumer c,long position,String now) {
        db.update("INSERT INTO l2_inbox_gaps VALUES(?,?,?,?)",c.ref(),c.received(),position,now);
        db.update("UPDATE l2_inbox_consumers SET baseline=?,received=? WHERE ref=?",position,position,c.ref());
    }
    public void issued(String consumer,String delivery) { db.update("INSERT OR IGNORE INTO l2_inbox_issued VALUES(?,?)",consumer,delivery); }
    public boolean wasIssued(String consumer,String delivery) { return db.queryOne("SELECT 1 FROM l2_inbox_issued WHERE consumer=? AND delivery_id=?",r->true,consumer,delivery)!=null; }
    public void markRead(String mailbox,String delivery,String now) { db.update("INSERT OR IGNORE INTO l2_inbox_reads VALUES(?,?,?)",mailbox,delivery,now); }
    public String readAt(String mailbox,String delivery) { return db.queryOne("SELECT read_at FROM l2_inbox_reads WHERE mailbox=? AND delivery_id=?",r->r.getString(1),mailbox,delivery); }
}
