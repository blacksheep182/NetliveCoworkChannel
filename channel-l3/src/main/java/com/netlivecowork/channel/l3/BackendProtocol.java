package com.netlivecowork.channel.l3;

import com.fasterxml.jackson.databind.JsonNode;
import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.infra.Db;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/** Dedicated personal-text-v1 adapter; service metadata and actual actor are independently verified. */
final class BackendProtocol {
    static final String PREFIX = "/api/v1/channel/v1";
    private final BackendTransport client;
    private final BackendIdentityResolver identities;
    private final Clock clock;
    private final Db db;
    BackendProtocol(BackendTransport client, BackendIdentityResolver identities, Clock clock, Db db) {
        this.client = client; this.identities = identities; this.clock = clock; this.db = db;
        db.ddl("""
            CREATE TABLE IF NOT EXISTS backend_request_bindings(
              operation_ref TEXT PRIMARY KEY, tenant_ref TEXT NOT NULL, workspace_ref TEXT NOT NULL,
              principal_ref TEXT NOT NULL, request_digest TEXT NOT NULL, sink_ref TEXT NOT NULL,
              backend_id TEXT NOT NULL, client_id TEXT NOT NULL, accepted_receipt TEXT NOT NULL)
            """);
        if (!db.query("PRAGMA table_info(backend_request_bindings)", rs -> rs.getString("name"))
                .contains("sink_revision")) {
            db.ddl("ALTER TABLE backend_request_bindings ADD COLUMN sink_revision INTEGER NOT NULL DEFAULT 0");
        }
        if(!db.query("PRAGMA table_info(backend_request_bindings)",rs->rs.getString("name")).contains("interaction_ref"))
            db.ddl("ALTER TABLE backend_request_bindings ADD COLUMN interaction_ref TEXT");
    }
    BackendIdentityResolver.Registration registration(BackendService service) {
        var r = identities.registration(service.serviceRef());
        if (r == null) throw failure("backend identity adapter is unavailable");
        return r;
    }
    JsonNode capabilities(BackendService service) throws IOException, InterruptedException {
        var r = registration(service);
        JsonNode n = successful(client.exchange(service,"GET",PREFIX + "/capabilities",null,
            Map.of("X-Channel-Features","input-semantics-v1,input-receipts-v1,session-workspaces-v1,interactions-v1")));
        Json.onlyFields(n, "protocol_version", "profile", "backend_id", "features", "limits", "retention", "auth");
        require("1".equals(Json.text(n,"protocol_version")) && "personal-text-v1".equals(Json.text(n,"profile"))
            && r.backendId().equals(Json.text(n,"backend_id")), "backend protocol identity mismatch");
        for (String f : new String[]{"resource_facts","delegated_calls","session_read","message_submit",
            "request_lookup","event_push","event_replay"}) require(n.path("features").path(f).isBoolean()
                && n.path("features").path(f).booleanValue(), "required backend feature is unavailable");
        require("bearer".equals(n.path("auth").path("service").asText())
            && "backend-adapter".equals(n.path("auth").path("delegation").asText())
            && r.adapterId().equals(n.path("auth").path("adapter_id").asText()), "backend authentication adapter mismatch");
        require(n.path("retention").path("request_min_seconds").asLong() > 0
            && n.path("retention").path("acked_event_min_seconds").asLong() > 0,
            "backend retention contract is unavailable");
        return n;
    }
    TargetOwnership facts(BackendService service, TargetRegistration target) throws IOException, InterruptedException {
        capabilities(service);
        var r = registration(service);
        JsonNode n = successful(client.exchange(service,"GET",sessionPath(target.sessionId()) + "/facts",null,
            epoch(target.resourceEpoch())));
        Json.onlyFields(n,"backend_id","tenant_id","workspace_id","session_id","resource_epoch","owner",
            "revision","observed_at","valid_until","capabilities","delegation");
        require(r.backendId().equals(Json.text(n,"backend_id")) && r.tenantId().equals(Json.text(n,"tenant_id"))
            && r.workspaceId().equals(Json.text(n,"workspace_id")) && target.sessionId().equals(Json.text(n,"session_id"))
            && target.resourceEpoch() == Json.reqLong(n,"resource_epoch"), "backend resource identity mismatch");
        Instant expiry = Instant.parse(Json.text(n,"valid_until"));
        require(expiry.isAfter(clock.instant()) && !Instant.parse(Json.text(n,"observed_at")).isAfter(expiry),
            "backend facts have expired");
        long revision = Json.reqLong(n,"revision");
        require(revision > 0, "invalid backend ownership revision");
        JsonNode owner = Json.obj(n,"owner");
        Json.onlyFields(owner,"subject_issuer","subject");
        String principal = identities.principalRef(service.serviceRef(),Json.text(owner,"subject_issuer"),Json.text(owner,"subject"));
        require(principal != null && !principal.isBlank(), "backend owner cannot be mapped");
        return new TargetOwnership(target.targetRef(),principal,
            r.backendId() + "/" + target.sessionId() + "/" + target.resourceEpoch() + "/" + revision,
            target.resourceEpoch(),revision,expiry);
    }
    SessionCreationBackend creationBackend(BackendRegistry registry,String sink) {
        return new SessionCreationBackend(db,clock,client,identities,registry,this,sink);
    }
    SessionWorkspace workspace(BackendService service,String id,String label,boolean canCreate) {
        if(!id.matches("ws-[a-f0-9]{32}"))throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE,"Invalid workspace identity");
        String ref="sws-"+com.netlivecowork.channel.infra.Digests.sha256(service.serviceRef()+"\u0000"+registration(service).backendId()+"\u0000"+id);
        return new SessionWorkspace(ref,label,canCreate);
    }
    OwnedSessionCatalog.Discovery discoverOwnedSessions(BackendRegistry registry, TrustedCall call)
            throws IOException, InterruptedException {
        return new OwnedSessionCatalog(client,identities,clock,registry,this).discover(call);
    }
    com.netlivecowork.channel.contract.SessionAccess.Manager sessionManager(BackendService service,String proof) throws IOException,InterruptedException {
        if(service==null || !service.enabled() || proof==null || proof.isBlank() || proof.length()>2048)
            throw new com.netlivecowork.channel.contract.ChannelContractException(ChannelErrorCode.AUTH_REQUIRED,"Current CoWork W3 authentication is required");
        var supported=capabilities(service);
        require(supported.path("features").path("session_catalog").asBoolean(false),"backend session catalog is unavailable");
        var reply=client.exchange(service,"GET",PREFIX+"/session-catalog",null,Map.of("X-Channel-Delegation",proof));
        if(reply.status()==401 || reply.status()==403)throw new com.netlivecowork.channel.contract.ChannelContractException(ChannelErrorCode.AUTH_REQUIRED,"Current CoWork W3 authentication is required");
        var body=successful(reply);Json.onlyFields(body,"backend_id","principal","items","next_cursor");
        var identity=Json.obj(body,"principal");Json.onlyFields(identity,"subject_issuer","subject");
        String issuer=Json.text(identity,"subject_issuer"),subject=Json.text(identity,"subject");
        String principal=identities.principalRef(service.serviceRef(),issuer,subject);
        if(!registration(service).backendId().equals(Json.text(body,"backend_id")) || principal==null)
            throw new com.netlivecowork.channel.contract.ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"This actual CoWork user has no registered Channel identity");
        return new com.netlivecowork.channel.contract.SessionAccess.Manager(principal,com.netlivecowork.channel.infra.Ids.next("cowork-auth"),clock.instant().plusSeconds(30),issuer,subject);
    }
    record Authorized(Map<String,String> headers, String digest) {}
    Authorized authorize(BackendService service, TargetRegistration target, TrustedCall call, String action,
                         String method, String path, JsonNode body, String requestKey) {
        var r = registration(service);
        var binding = new BackendCallBinding(r.backendId(),r.clientId(),r.tenantId(),r.workspaceId(),
            target.sessionId(),target.resourceEpoch(),action,method,path,ProtocolJson.digest(body),requestKey);
        var proof = identities.obtain(call,binding);
        require(proof != null && proof.protectedProof() != null && !proof.protectedProof().isBlank()
            && proof.validUntil() != null && proof.validUntil().isAfter(clock.instant()), "execution delegation is unavailable");
        require(call.principalRef().equals(identities.principalRef(service.serviceRef(),proof.subjectIssuer(),proof.subject())),
            "execution delegation actor does not match trusted call");
        var digestBody = Json.obj().put("backend_id",r.backendId()).put("client_id",r.clientId())
            .put("tenant_id",r.tenantId()).put("workspace_id",r.workspaceId()).put("session_id",target.sessionId())
            .put("resource_epoch",target.resourceEpoch()).put("subject_issuer",proof.subjectIssuer())
            .put("subject",proof.subject()).put("action",action);
        digestBody.set("body",body);
        return new Authorized(Map.of("X-Expected-Resource-Epoch",Long.toString(target.resourceEpoch()),
            "X-Channel-Delegation",proof.protectedProof()),ProtocolJson.digest(digestBody));
    }
    void bind(Dispatch row, TrustedCall call, Authorized auth, String sink, ReceiptPacket accepted) {
        Long registeredRevision = db.queryOne("SELECT revision FROM event_sink_registrations WHERE sink_ref=? AND service_ref=?",
            rs -> rs.getLong(1),sink,row.serviceRef());
        db.update("""
            INSERT OR IGNORE INTO backend_request_bindings(operation_ref,tenant_ref,workspace_ref,principal_ref,
              request_digest,sink_ref,backend_id,client_id,accepted_receipt,sink_revision) VALUES(?,?,?,?,?,?,?,?,?,?)
            """,row.operationRef(),call.tenantRef(),call.workspaceRef(),call.principalRef(),auth.digest(),sink,
            registrationFor(row).backendId(),registrationFor(row).clientId(),ReceiptCodec.encode(accepted),
            registeredRevision == null ? 0L : registeredRevision);
        String digest = binding(row.operationRef(),call,"request_digest");
        require(auth.digest().equals(digest), "original execution identity or request changed");
    }
    // Registration is persisted at first acceptance boundary; lookup never silently switches backend/client.
    private BackendIdentityResolver.Registration registrationFor(Dispatch row) {
        var r = identities.registration(row.serviceRef());
        if (r == null) throw failure("backend identity adapter is unavailable");
        return r;
    }
    private String binding(String operationRef, TrustedCall call, String column) {
        return db.queryOne("SELECT " + column + " FROM backend_request_bindings WHERE operation_ref=?"
            + " AND tenant_ref=? AND workspace_ref=? AND principal_ref=?",rs -> rs.getString(1),operationRef,
            call.tenantRef(),call.workspaceRef(),call.principalRef());
    }

    void bindInteraction(String operation,String interaction) {
        db.update("UPDATE backend_request_bindings SET interaction_ref=? WHERE operation_ref=? AND (interaction_ref IS NULL OR interaction_ref=?)",interaction,operation,interaction);
    }

    OperationLookup lookupInteraction(BackendService service,TargetRegistration target,Dispatch row,TrustedCall call,DispatchStore dispatches)
            throws IOException,InterruptedException {
        require(target!=null && target.resourceEpoch()==row.resourceEpoch(),"Original interaction resource changed");
        require(target!=null&&row.targetRef().equals(target.targetRef())&&row.sessionId().equals(target.sessionId())&&row.resourceEpoch()==target.resourceEpoch(),"Original interaction resource changed");
        var registration=registration(service);
        require(registration.backendId().equals(binding(row.operationRef(),call,"backend_id"))&&registration.clientId().equals(binding(row.operationRef(),call,"client_id")),"Original backend identity changed");
        var path=sessionPath(row.sessionId())+"/interaction-responses/"+PythonBackendClient.encode(row.requestKey());
        var auth=authorize(service,target,call,"session.read","GET",path,Json.obj(),null);
        return interactionResult(successful(client.exchange(service,"GET",path,null,auth.headers())),row,call,dispatches);
    }
    OperationLookup lookupResume(BackendService service,TargetRegistration target,Dispatch row,TrustedCall call,DispatchStore dispatches)
            throws IOException,InterruptedException {
        require(target!=null && row.targetRef().equals(target.targetRef())
            && row.sessionId().equals(target.sessionId()) && row.resourceEpoch()==target.resourceEpoch(),
            "Original resume resource changed");
        var registration=registration(service);
        require(registration.backendId().equals(binding(row.operationRef(),call,"backend_id"))
            && registration.clientId().equals(binding(row.operationRef(),call,"client_id")),
            "Original backend identity changed");
        String path=sessionPath(row.sessionId())+"/resumptions/"+PythonBackendClient.encode(row.requestKey());
        var auth=authorize(service,target,call,"session.read","GET",path,Json.obj(),null);
        return resumeResult(successful(client.exchange(service,"GET",path,null,auth.headers())),row,call,dispatches);
    }
    OperationLookup resumeResult(JsonNode node,Dispatch row,TrustedCall call,DispatchStore dispatches) {
        Json.onlyFields(node,"request_key","status","request_digest");
        require(row.requestKey().equals(Json.text(node,"request_key")),"Resume result correlation mismatch");
        String status=Json.text(node,"status");
        if("unknown".equals(status))return unknown(row.operationRef());
        require("accepted".equals(status),"Invalid resume result status");
        require(java.util.Objects.equals(binding(row.operationRef(),call,"request_digest"),Json.text(node,"request_digest")),
            "Resume request digest mismatch");
        var original=ReceiptCodec.decode(binding(row.operationRef(),call,"accepted_receipt"));
        var receipt=new ReceiptPacket(original.version(),com.netlivecowork.channel.infra.Ids.next("receipt"),clock.instant(),
            original.correlation(),original.context(),original.producer(),
            new ReceiptPayload.Completed(new OperationResult.SessionResume(row.targetRef())));
        dispatches.recordAccepted(row.operationRef(),receipt);
        return new OperationLookup(row.operationRef(),DispatchStatus.AGENT_ACCEPTED,receipt,null);
    }

    OperationLookup interactionResult(JsonNode node,Dispatch row,TrustedCall call,DispatchStore dispatches) {
        Json.onlyFields(node,"request_key","status","request_digest","source_event_id");
        require(row.requestKey().equals(Json.text(node,"request_key")),"Interaction result correlation mismatch");
        String status=Json.text(node,"status");require(java.util.Set.of("applied","unknown").contains(status),"Invalid interaction result status");
        if(!"applied".equals(status))return unknown(row.operationRef());
        require(java.util.Objects.equals(binding(row.operationRef(),call,"request_digest"),Json.text(node,"request_digest")),"Interaction result digest mismatch");
        require(Json.text(node,"source_event_id").matches("[A-Za-z0-9._~-]{1,128}"),"Interaction decision evidence is missing");
        var ref=binding(row.operationRef(),call,"interaction_ref");require(ref!=null,"Original interaction binding is missing");
        var original=ReceiptCodec.decode(binding(row.operationRef(),call,"accepted_receipt"));
        var receipt=new ReceiptPacket(original.version(),com.netlivecowork.channel.infra.Ids.next("receipt"),clock.instant(),original.correlation(),original.context(),original.producer(),
            new ReceiptPayload.Completed(new OperationResult.InteractionRespond(ref)));
        dispatches.recordAccepted(row.operationRef(),receipt);
        return new OperationLookup(row.operationRef(),DispatchStatus.AGENT_ACCEPTED,receipt,null);
    }
    OperationLookup lookup(BackendService service, Dispatch row, TrustedCall call, DispatchStore dispatches)
        throws IOException, InterruptedException {
        String digest = binding(row.operationRef(),call,"request_digest");
        require(digest != null,"original execution binding is unavailable");
        var r = registration(service);
        require(r.backendId().equals(binding(row.operationRef(),call,"backend_id"))
            && r.clientId().equals(binding(row.operationRef(),call,"client_id")),"original backend identity changed");
        Map<String,String> headers = new java.util.HashMap<>(epoch(row.resourceEpoch()));
        if (capabilities(service).path("features").path("input_receipts").asBoolean(false))
            headers.put("X-Channel-Features", "input-receipts-v1");
        JsonNode n = successful(client.exchange(service,"GET",sessionPath(row.sessionId()) + "/requests/"
            + PythonBackendClient.encode(row.requestKey()),null,headers));
        return lookupResult(n, row, call, dispatches);
    }
    OperationLookup lookupResult(JsonNode n, Dispatch row, TrustedCall call, DispatchStore dispatches) {
        String digest = binding(row.operationRef(),call,"request_digest");
        require(digest != null,"original execution binding is unavailable");
        require(row.requestKey().equals(Json.text(n,"request_key")), "request lookup correlation mismatch");
        String status = Json.text(n,"status");
        if ("unknown".equals(status)) return unknown(row.operationRef());
        require(digest.equals(Json.text(n,"request_digest")), "request lookup digest mismatch");
        Instant.parse(Json.text(n,"retain_until"));
        if ("accepted".equals(status)) {
            Instant.parse(Json.text(n,"accepted_at"));
            String execution = Json.text(Json.obj(n,"execution"),"state");
            require(java.util.Set.of("pending","accepted","unknown").contains(execution),"invalid execution state");
            ReceiptPacket receipt = ReceiptCodec.decode(binding(row.operationRef(),call,"accepted_receipt"));
            dispatches.recordAccepted(row.operationRef(),receipt);
            return new OperationLookup(row.operationRef(),DispatchStatus.AGENT_ACCEPTED,receipt,null);
        }
        if ("rejected".equals(status)) {
            Json.onlyFields(n,"request_key","request_digest","status","error","retain_until");
            JsonNode error = Json.obj(n,"error");
            ChannelError rejected = rejectionError(error);
            ReceiptPacket original = ReceiptCodec.decode(binding(row.operationRef(),call,"accepted_receipt"));
            ReceiptPacket receipt = new ReceiptPacket(original.version(),
                com.netlivecowork.channel.infra.Ids.next("receipt"),clock.instant(),original.correlation(),
                original.context(),original.producer(),new ReceiptPayload.Rejected(rejected));
            dispatches.recordRejected(row.operationRef(),receipt,rejected.message());
            return new OperationLookup(row.operationRef(),DispatchStatus.NOT_SENT,receipt,rejected);
        }
        // A malformed or unknown response never becomes authority to resend.
        return unknown(row.operationRef());
    }
    JsonNode registerSink(BackendService service, String sink, String key, long revision, String receiver,
                          String callback, String authBinding, boolean enabled) throws IOException, InterruptedException {
        capabilities(service);
        var body = Json.obj().put("request_key",key).put("expected_revision",revision).put("receiver_id",receiver)
            .put("callback_url",callback).put("callback_auth_binding_ref",authBinding).put("enabled",enabled);
        JsonNode result = successful(client.exchange(service,"PUT",PREFIX + "/event-sinks/" + PythonBackendClient.encode(sink),
            Json.write(body),Map.of()));
        require(sink.equals(Json.text(result,"event_sink_ref")) && receiver.equals(Json.text(result,"receiver_id"))
            && Json.reqLong(result,"revision") > 0,"sink registration correlation mismatch");
        return result;
    }
    JsonNode replay(BackendService service, Dispatch row, TrustedCall call, String replayKey, String cursor, int limit)
        throws IOException, InterruptedException {
        require(binding(row.operationRef(),call,"request_digest") != null,"original execution binding is unavailable");
        require(limit >= 1 && limit <= 1000,"invalid replay page limit");
        var body = Json.obj().put("replay_key",replayKey).put("limit",limit);
        if (cursor != null) body.put("after_cursor",cursor);
        JsonNode result = successful(client.exchange(service,"POST",sessionPath(row.sessionId()) + "/requests/"
            + PythonBackendClient.encode(row.requestKey()) + "/event-replays",Json.write(body),epoch(row.resourceEpoch())));
        require(row.requestKey().equals(Json.text(result,"request_key")) && replayKey.equals(Json.text(result,"replay_key")),
            "replay correlation mismatch");
        return result;
    }
    static boolean accepted(PythonBackendClient.Reply reply, String requestKey) {
        try {
            if (reply.status() != 202) return false;
            JsonNode n = Json.read(reply.body());
            Json.onlyFields(n,"request_key","status","accepted_at","retain_until");
            return requestKey.equals(Json.text(n,"request_key")) && "accepted".equals(Json.text(n,"status"))
                && !Instant.parse(Json.text(n,"retain_until")).isBefore(Instant.parse(Json.text(n,"accepted_at")));
        } catch (RuntimeException e) { return false; }
    }
    static boolean definiteRejection(PythonBackendClient.Reply reply, String requestKey) {
        try {
            JsonNode n = Json.read(reply.body());
            Json.onlyFields(n,"error","request_key");
            if (!requestKey.equals(Json.text(n,"request_key"))) return false;
            JsonNode error = Json.obj(n,"error");
            Json.onlyFields(error,"code","message","retryable","retry_after_ms");
            return java.util.Set.of("INVALID_REQUEST","UNSUPPORTED_CONTENT","INTERACTION_PENDING","SESSION_BUSY",
                "CONTENT_TOO_LARGE","UNSUPPORTED_CAPABILITY","RATE_LIMITED","INTERACTION_STALE","PERMISSION_DENIED","REQUEST_CONFLICT").contains(Json.text(error,"code"))
                && error.path("retryable").isBoolean() && !Json.text(error,"message").isBlank();
        } catch (RuntimeException e) { return false; }
    }
    /** 仅在关联键/摘要已验证后使用；直接回执与原请求查询必须给出相同的拒绝语义。 */
    static ChannelError rejectionError(JsonNode error) {
        Json.onlyFields(error,"code","message","retryable","retry_after_ms");
        require(error.path("retryable").isBoolean(),"invalid rejection retryability");
        String backendCode = Json.text(error,"code");
        ChannelErrorCode code = switch (backendCode) {
            case "INVALID_REQUEST", "CONTENT_TOO_LARGE" -> ChannelErrorCode.INVALID_ARGUMENT;
            case "INTERACTION_STALE" -> ChannelErrorCode.INTERACTION_STALE;
            case "REQUEST_CONFLICT" -> ChannelErrorCode.REQUEST_CONFLICT;
            case "UNSUPPORTED_CONTENT" -> ChannelErrorCode.UNSUPPORTED_CONTENT;
            case "UNSUPPORTED_CAPABILITY" -> ChannelErrorCode.UNSUPPORTED_CAPABILITY;
            case "SESSION_BUSY", "INTERACTION_PENDING" -> ChannelErrorCode.UNAVAILABLE;
            case "PERMISSION_DENIED", "DELEGATION_UNAVAILABLE" -> ChannelErrorCode.PERMISSION_DENIED;
            case "RATE_LIMITED" -> ChannelErrorCode.RATE_LIMITED;
            default -> throw failure("unrecognized durable rejection");
        };
        String message = Json.text(error,"message");
        if ("INTERACTION_PENDING".equals(backendCode)) message = "会话正在等待人工交互，请先在 CoWork 中完成待处理的交互，再发送消息。";
        if ("SESSION_BUSY".equals(backendCode)) message = "会话仍在运行，暂不接受新一轮消息。请等待本轮完成后再发送。";
        Long retryAfter = Json.optLong(error,"retry_after_ms",0L);
        require(retryAfter >= 0,"invalid rejection retry delay");
        return new ChannelError(code.name(),message,error.path("retryable").booleanValue(),null,
            retryAfter == 0 ? null : retryAfter);
    }

    static com.netlivecowork.channel.contract.SessionExecutionState sessionExecution(JsonNode body) {
        // Older snapshots may omit execution state; absence must never become an invented idle state.
        if (!body.has("status") && !body.has("revision") && !body.has("observed_at")) return null;
        try {
            Json.onlyFields(body, "session_id", "resource_epoch", "title", "status", "revision", "observed_at", "raw_status", "input", "workspace");
            com.netlivecowork.channel.contract.InputAvailability input = null;
            if (body.has("input")) {
                JsonNode action = Json.obj(body, "input");
                Json.onlyFields(action, "allowed", "mode", "reason");
                require(action.path("allowed").isBoolean(), "invalid input availability");
                input = new com.netlivecowork.channel.contract.InputAvailability(action.path("allowed").booleanValue(),
                    Json.text(action,"mode"), Json.text(action,"reason"));
            }
            return new com.netlivecowork.channel.contract.SessionExecutionState(Json.text(body,"status"),
                Json.reqLong(body,"revision"),Instant.parse(Json.text(body,"observed_at")),Json.optText(body,"raw_status"),input);
        } catch (RuntimeException e) { throw failure("backend session execution snapshot is invalid"); }
    }

    static String sessionPath(String sessionId) { return PREFIX + "/sessions/" + PythonBackendClient.encode(sessionId); }
    static Map<String,String> epoch(long epoch) { return Map.of("X-Expected-Resource-Epoch",Long.toString(epoch)); }
    static JsonNode successful(PythonBackendClient.Reply reply) {
        if (!reply.success()) throw failure("backend protocol request did not succeed");
        return Json.read(reply.body());
    }
    static OperationLookup unknown(String operationRef) {
        return new OperationLookup(operationRef,DispatchStatus.OUTCOME_UNKNOWN,null,
            ChannelErrorCode.OUTCOME_UNKNOWN.error("original backend request remains unresolved"));
    }
    private static void require(boolean condition, String message) { if (!condition) throw failure(message); }
    private static ChannelContractException failure(String message) {
        return new ChannelContractException(ChannelErrorCode.UNAVAILABLE,message);
    }
}
