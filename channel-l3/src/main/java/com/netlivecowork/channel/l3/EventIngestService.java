package com.netlivecowork.channel.l3;

import com.netlivecowork.channel.contract.AgentEvent;
import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelError;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.EventSource;
import com.netlivecowork.channel.contract.VerifiedBackendEvent;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.json.PacketJson;
import com.netlivecowork.channel.infra.Db;
import com.netlivecowork.channel.infra.Digests;
import com.netlivecowork.channel.infra.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 后端事件接入（l3-backend.md §4.1～4.2）。
 *
 * <p>只信已登记的接收端与服务身份，不信回调自报身份（INV-29）；resourceRef 由服务 + 会话 +
 * 资源代次组成，覆盖原始事件 ID 的完整命名空间；operationRef 只从原派发记录解析，无依据时留空，
 * 绝不虚构（INV-30）。只有事件与可恢复转交记录提交后才确认 Agent（INV-31）。
 */
public final class EventIngestService {

    private final BackendRegistry registry;
    private final Db db;
    private final DispatchStore dispatches;
    private final EventStore events;
    private final Map<String, Sink> sinks = new ConcurrentHashMap<>();

    EventIngestService(Db db, Clock clock, L3Settings settings, BackendRegistry registry,
                       DispatchStore dispatches) {
        this.db = db;
        this.registry = registry;
        db.ddl("""
            CREATE TABLE IF NOT EXISTS event_sink_registrations(
              sink_ref TEXT PRIMARY KEY, service_ref TEXT NOT NULL, revision INTEGER NOT NULL)
            """);
        db.ddl("CREATE TABLE IF NOT EXISTS backend_creation_events(request_key TEXT PRIMARY KEY,service_ref TEXT NOT NULL,target_ref TEXT NOT NULL,operation_ref TEXT NOT NULL,sink_ref TEXT NOT NULL,sink_revision INTEGER NOT NULL,resource_epoch INTEGER NOT NULL)");
        this.dispatches = dispatches;
        this.events = new EventStore(db, clock, settings);
    }

    /**
     * 登记事件接收端。{@code serviceRef} 把接收端绑定到确定的后端服务：回调里的 sessionId 只有在该服务
     * 已登记资源内才被接受；token 只保存摘要，不保存明文（INV-52）。
     */
    public void registerSink(String sinkRef, String serviceRef, String token) {
        registerSink(sinkRef, serviceRef, token, 1L);
    }

    /**
     * 登记事件接收端及其传输版本。历史版本通过原派发绑定复验；当前凭据始终来自本登记。
     * sink 固定后端身份且版本不回退，受信路径更新不改变原请求接收端（INV-64）。
     */
    public void registerSink(String sinkRef, String serviceRef, String token, long revision) {
        if (sinkRef == null || sinkRef.isBlank() || serviceRef == null || serviceRef.isBlank()
            || token == null || token.isBlank()) {
            throw new IllegalArgumentException("event sink registration requires sinkRef, serviceRef and token");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("event sink revision must be >= 1");
        }
        db.tx(connection -> {
            String priorService = db.queryOne("SELECT service_ref FROM event_sink_registrations WHERE sink_ref=?",
                rs -> rs.getString(1),sinkRef);
            if (priorService != null && !priorService.equals(serviceRef))
                throw new IllegalArgumentException("event sink backend identity cannot change");
            Long priorRevision = db.queryOne("SELECT revision FROM event_sink_registrations WHERE sink_ref=?",
                rs -> rs.getLong(1),sinkRef);
            if (priorRevision != null && revision < priorRevision)
                throw new IllegalArgumentException("event sink revision cannot move backwards");
            db.update("INSERT INTO event_sink_registrations VALUES(?,?,?) ON CONFLICT(sink_ref) DO UPDATE SET revision=excluded.revision",
                sinkRef,serviceRef,revision);
            return null;
        });
        sinks.put(sinkRef, new Sink(serviceRef, Digests.sha256(token), revision));
    }

    /**
     * 兼容入口（无显式传输头断言）：仍验证 sink/凭证与资源归属，但不核对代次。
     * HTTP 回调必须走带 epoch/revision 的重载，不能让客户端省略来源绑定。
     */
    IngestResult ingestFromService(String serviceRef,String sinkRef,String token,long epoch,long revision,String body) {
        Sink sink=sinks.get(sinkRef);
        if(sink==null || !sink.serviceRef().equals(serviceRef))
            return IngestResult.rejected(ChannelErrorCode.PERMISSION_DENIED.error("Device event belongs to another service"));
        return ingest(sinkRef,token,epoch,revision,body);
    }

    public IngestResult ingest(String sinkRef, String presentedToken, String bodyJson) {
        return ingest(sinkRef, presentedToken, 0L, 0L, bodyJson);
    }

    /**
     * 回调入口：验证 sink/凭证与传输记录版本 → 验证事件 → 核对资源代次与原请求关联 →
     * 持久接管 → 确认（backend-http-v1 §7.1）。{@code epoch} / {@code revision} 为 0 表示调用方
     * 未断言，只有进程内既有调用可以使用；HTTP 入口始终传入实际头值。
     */
    public IngestResult ingest(String sinkRef, String presentedToken, long epoch, long revision,
                               String bodyJson) {
        Sink sink = sinkRef == null ? null : sinks.get(sinkRef);
        if (sink == null) {
            return IngestResult.rejected(ChannelErrorCode.AUTH_REQUIRED.error("event sink is not registered"));
        }
        if (!matches(sink.tokenDigest(), presentedToken)) {
            return IngestResult.rejected(ChannelErrorCode.AUTH_REQUIRED.error("event sink credential mismatch"));
        }
        if (revision > sink.revision()) {
            return IngestResult.rejected(ChannelErrorCode.REQUEST_CONFLICT.error(
                "event sink revision does not match the registered transport record"));
        }
        AgentEvent event;
        try {
            event = epoch > 0 || revision > 0 ? BackendEventCodec.decode(bodyJson) : PacketJson.decAgentEvent(bodyJson);
        } catch (ChannelContractException e) {
            // 未知版本/类型/字段一律拒绝，不生成伪完整事件（INV-33）。
            return IngestResult.rejected(ChannelError.of(ChannelErrorCode.INVALID_ARGUMENT, e.getMessage()));
        }
        TargetRegistration target = registry.targetBySession(sink.serviceRef(), event.resource().sessionId());
        if (target == null) {
            // 填了合法 sessionId 也不跳过资源归属（INV-29）。
            return IngestResult.rejected(ChannelErrorCode.PERMISSION_DENIED.error(
                "event session is not registered for this event sink"));
        }
        if (epoch > 0 && epoch != target.resourceEpoch()) {
            // 代次不符：事件属于已退役的资源化身，不能按最新代次接收（INV-29、51）。
            return IngestResult.rejected(ChannelErrorCode.REQUEST_CONFLICT.error(
                "event resource epoch is not the registered current epoch"));
        }
        if (!target.enabled()) {
            return IngestResult.rejected(ChannelErrorCode.PERMISSION_DENIED.error(
                "event target is disabled"));
        }
        String requestKey = event.correlation() == null ? null : event.correlation().requestKey();
        String operationRef = dispatches.operationRefForRequest(sink.serviceRef(), target.sessionId(), requestKey);
        if(operationRef==null)operationRef=db.queryOne("SELECT operation_ref FROM backend_creation_events WHERE request_key=? AND service_ref=? AND target_ref=? AND resource_epoch=? AND sink_ref=?",
            r->r.getString(1),requestKey,sink.serviceRef(),target.targetRef(),target.resourceEpoch(),sinkRef);
        if (revision > 0 && revision < sink.revision()) {
            // A trusted path repair keeps the original receiver and request binding.
            // Past versions are admitted only for that exact durable dispatch, never
            // merely because the caller knows a service token or a session ID.
            Dispatch original = dispatches.load(operationRef);
            Long originalRevision = db.queryOne("SELECT sink_revision FROM backend_request_bindings WHERE operation_ref=? AND sink_ref=?",
                rs -> rs.getLong(1),operationRef,sinkRef);
            Long creationRevision=db.queryOne("SELECT sink_revision FROM backend_creation_events WHERE operation_ref=? AND sink_ref=? AND target_ref=? AND resource_epoch=?",r->r.getLong(1),operationRef,sinkRef,target.targetRef(),epoch);
            if (creationRevision==null && (original == null || original.resourceEpoch() != epoch
                || originalRevision == null || originalRevision != revision
                || (original.phase() != Dispatch.Phase.SENT_UNKNOWN && original.phase() != Dispatch.Phase.AGENT_ACCEPTED)) || creationRevision!=null && creationRevision!=revision) {
                return IngestResult.rejected(ChannelErrorCode.REQUEST_CONFLICT.error(
                    "past sink revision has no matching original transport binding"));
            }
        }
        String resourceRef = target.resourceRef();
        String backendEventRef = Ids.next("backend-event");
        VerifiedBackendEvent verified = new VerifiedBackendEvent(backendEventRef,
            new EventSource(resourceRef, event.eventId()), operationRef, target.targetRef(), null,
            event.eventType(), event.occurredAt(), event.order(), event.payload());
        EventStore.InsertResult result = events.insert(new EventStore.EventDraft(backendEventRef, resourceRef,
            event.eventId(), target.targetRef(), operationRef, requestKey, event.eventType().wire(),
            event.occurredAt(), epoch > 0 || revision > 0 ? ProtocolJson.digest(Json.read(bodyJson)) : contentDigest(event), Json.write(PacketJson.enc(verified))));
        return switch (result) {
            case ACCEPTED, DUPLICATE -> IngestResult.accepted(event.eventId());
            case CONFLICT -> IngestResult.rejected(ChannelErrorCode.REQUEST_CONFLICT.error(
                "the same source event id was already received with different content"));
        };
    }

    VerifiedBackendEvent acceptSnapshot(TargetRegistration target,com.netlivecowork.channel.contract.AgentEvent event,String raw) {
        if(!target.enabled() || !target.sessionId().equals(event.resource().sessionId()))
            throw new com.netlivecowork.channel.contract.ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Interaction snapshot resource mismatch");
        String request=event.correlation()==null?null:event.correlation().requestKey();
        String operation=dispatches.operationRefForRequest(target.serviceRef(),target.sessionId(),request);
        var id=Ids.next("backend-event");
        var verified=new VerifiedBackendEvent(id,new EventSource(target.resourceRef(),event.eventId()),operation,target.targetRef(),null,
            event.eventType(),event.occurredAt(),event.order(),event.payload());
        var result=events.insert(new EventStore.EventDraft(id,target.resourceRef(),event.eventId(),target.targetRef(),operation,request,
            event.eventType().wire(),event.occurredAt(),ProtocolJson.digest(Json.read(raw)),Json.write(PacketJson.enc(verified))));
        if(result==EventStore.InsertResult.CONFLICT)throw new com.netlivecowork.channel.contract.ChannelContractException(ChannelErrorCode.REQUEST_CONFLICT,"Interaction source conflict");
        return db.queryOne("SELECT verified_json FROM backend_events WHERE resource_ref=? AND source_event_id=?",
            r->PacketJson.decVerifiedEvent(r.getString(1)),target.resourceRef(),event.eventId());
    }

    EventStore store() {
        return events;
    }

    private static String contentDigest(AgentEvent event) {
        return Digests.sha256(Json.write(PacketJson.enc(event)));
    }

    /** 常量时间比较摘要，不比较明文凭证。 */
    private static boolean matches(String expectedDigest, String presentedToken) {
        byte[] expected = expectedDigest.getBytes(StandardCharsets.US_ASCII);
        byte[] actual = Digests.sha256(presentedToken == null ? "" : presentedToken)
            .getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    private record Sink(String serviceRef, String tokenDigest, long revision) {
    }
}
