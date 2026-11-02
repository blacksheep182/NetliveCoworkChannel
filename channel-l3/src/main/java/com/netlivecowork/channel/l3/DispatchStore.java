package com.netlivecowork.channel.l3;

import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.CommandPacket;
import com.netlivecowork.channel.contract.CommandType;
import com.netlivecowork.channel.contract.ReceiptPacket;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.json.PacketJson;
import com.netlivecowork.channel.infra.Db;
import com.netlivecowork.channel.infra.Digests;
import com.netlivecowork.channel.infra.Ids;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 派发与核对记录（l3-routing-and-recovery.md §3、§5）。
 *
 * <p>写入前先把尝试登记为 SENT_UNKNOWN 并把请求写入事务提交（INV-67）：只有权威记录里不存在任何
 * 可能发送的尝试时才可给出 NOT_SENT。网络调用一律在事务之外（INV-20）。
 */
final class DispatchStore {

    private final Db db;
    private final Clock clock;

    DispatchStore(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
        db.ddl("""
            CREATE TABLE IF NOT EXISTS dispatches(
              operation_ref TEXT PRIMARY KEY,
              packet_id TEXT NOT NULL,
              command_type TEXT NOT NULL,
              target_ref TEXT NOT NULL,
              service_ref TEXT NOT NULL,
              session_id TEXT NOT NULL,
              resource_epoch INTEGER NOT NULL,
              request_key TEXT NOT NULL,
              payload_digest TEXT NOT NULL,
              phase TEXT NOT NULL,
              attempts INTEGER NOT NULL,
              owner TEXT,
              lease_until TEXT,
              receipt TEXT,
              last_error TEXT,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL)
            """,
            "CREATE INDEX IF NOT EXISTS dispatches_request_key ON dispatches(service_ref, session_id, request_key)",
            "CREATE TABLE IF NOT EXISTS dispatch_closures(operation_ref TEXT PRIMARY KEY,target_ref TEXT NOT NULL,tenant_ref TEXT NOT NULL,workspace_ref TEXT NOT NULL,payload_digest TEXT NOT NULL,closed_at TEXT NOT NULL)");
    }

    boolean closed(CommandPacket command,com.netlivecowork.channel.contract.TrustedCall call) {
        String digest=db.queryOne("SELECT payload_digest FROM dispatch_closures WHERE operation_ref=? AND tenant_ref=? AND workspace_ref=?",
            rs->rs.getString(1),command.context().operationRef(),call.tenantRef(),call.workspaceRef());
        if(digest!=null && !digest.equals(payloadDigest(command)))
            throw new com.netlivecowork.channel.contract.ChannelContractException(ChannelErrorCode.REQUEST_CONFLICT,"Original operation closure has different content");
        return digest!=null;
    }
    boolean closed(String operationRef,com.netlivecowork.channel.contract.TrustedCall call) {
        return db.queryOne("SELECT operation_ref FROM dispatch_closures WHERE operation_ref=? AND tenant_ref=? AND workspace_ref=?",
            rs->rs.getString(1),operationRef,call.tenantRef(),call.workspaceRef())!=null;
    }
    boolean closeUnsent(CommandPacket command,com.netlivecowork.channel.contract.TrustedCall call) {
        return db.tx(c->{
            if(closed(command,call))return true;
            String op=command.context().operationRef();Dispatch row=load(op);
            if(row!=null && (!row.payloadDigest().equals(payloadDigest(command)) || !row.mayAttempt()))return false;
            var target=db.queryOne("SELECT tenant_ref,workspace_ref FROM targets WHERE target_ref=?",
                rs->java.util.List.of(rs.getString(1),rs.getString(2)),command.context().targetRef());
            if(target!=null && (!target.get(0).equals(call.tenantRef())||!target.get(1).equals(call.workspaceRef())))return false;
            db.update("INSERT OR IGNORE INTO dispatch_closures VALUES(?,?,?,?,?,?)",op,command.context().targetRef(),call.tenantRef(),call.workspaceRef(),payloadDigest(command),clock.instant());
            return true;
        });
    }
    void recordPreflightRejection(String operationRef,ReceiptPacket receipt,String error){
        db.update("UPDATE dispatches SET phase='NOT_SENT',receipt=?,last_error=?,updated_at=? WHERE operation_ref=? AND phase IN ('REGISTERED','NOT_SENT') AND NOT EXISTS(SELECT 1 FROM dispatch_closures WHERE operation_ref=?)",
            receipt==null?null:ReceiptCodec.encode(receipt),error,clock.instant(),operationRef,operationRef);
    }

    Dispatch load(String operationRef) {
        if (operationRef == null || operationRef.isBlank()) {
            return null;
        }
        return db.queryOne("SELECT * FROM dispatches WHERE operation_ref=?", DispatchStore::map, operationRef);
    }

    /**
     * 首次写入前固定 operationRef → 实际服务/会话/资源代次/后端幂等键的关联（INV-19）。
     * requestKey 只在这里派生一次，之后一律沿用。
     */
    Dispatch create(CommandPacket command, TargetResolver.Resolved resolved) {
        String operationRef = command.context().operationRef();
        String serviceRef = resolved.service().serviceRef();
        String sessionId = resolved.target().sessionId();
        Instant now = clock.instant();
        db.update("""
            INSERT OR IGNORE INTO dispatches(operation_ref, packet_id, command_type, target_ref, service_ref,
              session_id, resource_epoch, request_key, payload_digest, phase, attempts, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,'REGISTERED',0,?,?)
            """,
            operationRef, command.packetId(), command.type().name(), resolved.target().targetRef(), serviceRef,
            sessionId, resolved.target().resourceEpoch(), requestKey(serviceRef, sessionId, operationRef),
            payloadDigest(command), now, now);
        return load(operationRef);
    }

    /** 网络写入前登记本次尝试；已离开可尝试阶段时不夺取所有权（并发收敛到同一关联）。 */
    Dispatch claim(String operationRef, String owner, Instant now, Duration lease) {
        return db.tx(connection -> {
            Dispatch row = load(operationRef);
            if (row == null || !row.mayAttempt()) {
                return null;
            }
            int changed=db.update("""
                UPDATE dispatches SET phase='SENT_UNKNOWN', attempts=attempts+1, owner=?, lease_until=?,
                  updated_at=? WHERE operation_ref=? AND phase IN ('REGISTERED','NOT_SENT') AND NOT EXISTS(SELECT 1 FROM dispatch_closures WHERE operation_ref=?)
                """,
                owner, now.plus(lease), now, operationRef, operationRef);
            return changed==1?load(operationRef):null;
        });
    }

    void recordAccepted(String operationRef, ReceiptPacket receipt) {
        finish(operationRef, Dispatch.Phase.AGENT_ACCEPTED.name(), receipt, null);
    }

    void recordRejected(String operationRef, ReceiptPacket receipt, String error) {
        finish(operationRef, Dispatch.Phase.NOT_SENT.name(), receipt, error);
    }

    void recordUnknown(String operationRef, ReceiptPacket receipt, String error) {
        finish(operationRef, Dispatch.Phase.SENT_UNKNOWN.name(), receipt, error);
    }

    void recordNotSent(String operationRef, ReceiptPacket receipt, String error) {
        finish(operationRef, Dispatch.Phase.NOT_SENT.name(), receipt, error);
    }

    void noteError(String operationRef, String error) {
        db.update("UPDATE dispatches SET last_error=?, updated_at=? WHERE operation_ref=?",
            error, clock.instant(), operationRef);
    }

    ReceiptPacket storedReceipt(Dispatch row) {
        if (row == null || row.receiptJson() == null || row.receiptJson().isBlank()) {
            return null;
        }
        try {
            return ReceiptCodec.decode(row.receiptJson());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 事件关联：只按原派发记录解析 operationRef，绝不从内容推断（INV-30）。 */
    String operationRefForRequest(String serviceRef, String sessionId, String requestKey) {
        if (requestKey == null || requestKey.isBlank()) {
            return null;
        }
        return db.queryOne("""
            SELECT operation_ref FROM dispatches
            WHERE service_ref=? AND session_id=? AND request_key=?
            ORDER BY created_at LIMIT 1
            """, rs -> Db.str(rs, "operation_ref"), serviceRef, sessionId, requestKey);
    }

    static String payloadDigest(CommandPacket command) {
        return Digests.sha256(Json.write(PacketJson.enc(command)));
    }

    private static String requestKey(String serviceRef, String sessionId, String operationRef) {
        // 去重键限定可信接入与资源域：同一 operationRef 在别的服务/会话下不是同一请求。
        String scope = serviceRef + "\u0000" + sessionId + "\u0000" + operationRef;
        return "rk-" + Digests.sha256(scope).substring(0, 32);
    }

    private void finish(String operationRef, String phase, ReceiptPacket receipt, String error) {
        db.update("""
            UPDATE dispatches SET phase=?, receipt=?, last_error=?, owner=NULL, lease_until=NULL, updated_at=?
            WHERE operation_ref=? AND (phase!='AGENT_ACCEPTED' OR ?='AGENT_ACCEPTED')
              AND NOT EXISTS(SELECT 1 FROM dispatch_closures WHERE operation_ref=?)
            """,
            phase, receipt == null ? null : ReceiptCodec.encode(receipt), error, clock.instant(), operationRef,phase,operationRef);
    }

    private static Dispatch map(ResultSet rs) throws java.sql.SQLException {
        return new Dispatch(Db.str(rs, "operation_ref"), Db.str(rs, "packet_id"),
            CommandType.valueOf(Db.str(rs, "command_type")), Db.str(rs, "target_ref"),
            Db.str(rs, "service_ref"), Db.str(rs, "session_id"), rs.getLong("resource_epoch"),
            Db.str(rs, "request_key"), Db.str(rs, "payload_digest"),
            Dispatch.Phase.valueOf(Db.str(rs, "phase")), rs.getInt("attempts"), Db.str(rs, "receipt"),
            Db.str(rs, "last_error"));
    }

    static String ownerTag() {
        return "l3-" + Ids.random(8);
    }
}
