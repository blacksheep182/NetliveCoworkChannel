package com.netlivecowork.channel.l3;

import com.netlivecowork.channel.contract.VerifiedBackendEvent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelError;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.CommandOutcome;
import com.netlivecowork.channel.contract.CommandPacket;
import com.netlivecowork.channel.contract.CommandPayload;
import com.netlivecowork.channel.contract.CommandType;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.Correlation;
import com.netlivecowork.channel.contract.DispatchStatus;
import com.netlivecowork.channel.contract.FileReadResult;
import com.netlivecowork.channel.contract.InteractionResponse;
import com.netlivecowork.channel.contract.Layer;
import com.netlivecowork.channel.contract.OperationLookup;
import com.netlivecowork.channel.contract.OperationResult;
import com.netlivecowork.channel.contract.PageRequest;
import com.netlivecowork.channel.contract.Producer;
import com.netlivecowork.channel.contract.QuestionAnswer;
import com.netlivecowork.channel.contract.ReceiptPacket;
import com.netlivecowork.channel.contract.ReceiptPayload;
import com.netlivecowork.channel.contract.SessionSummary;
import com.netlivecowork.channel.contract.SessionWatch;
import com.netlivecowork.channel.contract.SessionWatchRequest;
import com.netlivecowork.channel.contract.TargetSummary;
import com.netlivecowork.channel.contract.TaskStatus;
import com.netlivecowork.channel.contract.TrustedCall;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.infra.Db;
import com.netlivecowork.channel.infra.Ids;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * L2 → L3 后端访问端口实现。派发事实与业务回执分开：accepted 只在后端给出可核对受理时产生
 * （INV-22、25），写操作结果不确定时保留 unknown 并沿用原请求键（INV-23）。
 */
final class BackendAccessService implements com.netlivecowork.channel.contract.port.BackendAccessPort {

    private static final Producer PRODUCER = new Producer(Layer.L3, "l3-backend");
    /** 读操作可有界重试；写操作永不自动重试（l3-backend.md §3）。 */
    private static final int MAX_READ_ATTEMPTS = 3;

    private DeviceRegistry devices;
    private ConnectorHub connectorHub;
    void bindDevices(DeviceRegistry devices,ConnectorHub connectors){this.devices=devices;this.connectorHub=connectors;}
    public CompletionStage<Void> revokeDevice(String deviceRef,TrustedCall call){
        return CompletableFuture.runAsync(()->{devices.requireScope(deviceRef,call.tenantRef(),call.workspaceRef());connectorHub.revoke(deviceRef);},executor);
    }
    public CompletionStage<com.netlivecowork.channel.contract.DeviceAccess.Ticket> issueDeviceEnrollment(com.netlivecowork.channel.contract.DeviceAccess.Authorization grant){
        return CompletableFuture.supplyAsync(()->devices.issue(grant),executor);
    }
    public CompletionStage<Boolean> closeUnsent(CommandPacket command,TrustedCall call){
        return CompletableFuture.supplyAsync(()->dispatches.closeUnsent(command,call),executor);
    }
    private final BackendProtocol protocol;
    private final SessionCreationBackend creation;
    private final java.util.concurrent.ConcurrentHashMap<String,OwnedSessionCatalog.Discovery> catalogs=new java.util.concurrent.ConcurrentHashMap<>();
    private final L3Settings settings;
    private final Clock clock;
    private final BackendRegistry registry;
    private final DispatchStore dispatches;
    private final InteractionStore interactions;
    private EventIngestService interactionEvents;
    void bindInteractionEvents(EventIngestService value) { interactionEvents=value; }
    private final SessionWatchStore watches;
    private final BackendTransport client;
    private final TargetResolver resolver;
    private final Executor executor;
    private final String owner;
    private final Duration lease;
    BackendAccessService(Db db, Clock clock, L3Settings settings, BackendRegistry registry,
                         DispatchStore dispatches, InteractionStore interactions, SessionWatchStore watches,
                         BackendTransport client, Executor executor, BackendProtocol protocol) {
        this.protocol = protocol;
        this.creation = protocol.creationBackend(registry,settings.sinkRef());
        this.settings = settings;
        this.clock = clock;
        this.registry = registry;
        this.dispatches = dispatches;
        this.interactions = interactions;
        this.watches = watches;
        this.client = client;
        this.resolver = new TargetResolver(registry);
        this.executor = executor;
        this.owner = DispatchStore.ownerTag();
        this.lease = Duration.ofSeconds(settings.forwardLeaseSeconds());
    }

    public CompletionStage<List<com.netlivecowork.channel.contract.SessionWorkspace>> creationWorkspaces(TrustedCall call) {
        return CompletableFuture.supplyAsync(()->{try{return creation.workspaces(call);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE,"Workspace query interrupted");}catch(IOException e){throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE,"Workspace query unavailable");}},executor);
    }
    public CompletionStage<com.netlivecowork.channel.contract.SessionCreation.Created> createSession(TrustedCall call,String request,String workspace,String prompt,boolean lookupOnly) {
        return CompletableFuture.supplyAsync(()->{try{return creation.create(call,request,workspace,prompt,lookupOnly);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ChannelContractException(ChannelErrorCode.OUTCOME_UNKNOWN,"Query the original creation request");}catch(IOException e){throw new ChannelContractException(ChannelErrorCode.OUTCOME_UNKNOWN,"Query the original creation request");}},executor);
    }

    // ---- submit ---------------------------------------------------------------------

    @Override
    public CompletionStage<CommandOutcome> submitCommand(CommandPacket command, TrustedCall call) {
        return CompletableFuture.supplyAsync(() -> submit(command, call), executor);
    }

    private CommandOutcome submit(CommandPacket command, TrustedCall call) {
        String operationRef = command.context().operationRef();
        try {
            requireWriteType(command.type());
            if(dispatches.closed(command,call))return notSent(command,operationRef,
                ChannelErrorCode.REQUEST_CONFLICT.error("Original operation was durably closed without submission"));
            Dispatch existing = dispatches.load(operationRef);
            if (existing != null && !existing.payloadDigest().equals(DispatchStore.payloadDigest(command))) {
                // 同 operationRef 不同内容：不覆盖原关联、不发 HTTP（INV-10、19）。
                ChannelError error = ChannelError.of(ChannelErrorCode.REQUEST_CONFLICT,
                    "operationRef was first registered with a different payload", "operationRef");
                dispatches.noteError(operationRef, error.message());
                return notSent(command, operationRef, error);
            }
            if (existing != null && existing.phase() == Dispatch.Phase.AGENT_ACCEPTED) {
                ReceiptPacket stored = dispatches.storedReceipt(existing);
                if (stored != null) {
                    return CommandOutcome.accepted(operationRef, stored);
                }
                return CommandOutcome.unknown(operationRef,
                    ChannelErrorCode.OUTCOME_UNKNOWN.error("stored receipt is unavailable"));
            }
            if (existing != null && existing.phase() == Dispatch.Phase.SENT_UNKNOWN) {
                return CommandOutcome.unknown(operationRef, ChannelErrorCode.OUTCOME_UNKNOWN.error(
                    "a previous attempt may have reached the backend; reconcile the original request key"));
            }
            if(expired(call))return notSent(command,operationRef,ChannelErrorCode.UNAVAILABLE.error("call deadline has already passed"));
            TargetResolver.Resolved resolved = resolver.resolve(command.context().targetRef(),
                call.tenantRef(), call.workspaceRef());
            if (existing != null && !existing.matches(resolved)) {
                // 已固定的资源身份被重建：不静默改用新资源，也不假装已发送（INV-21、51）。
                ChannelError error = ChannelError.of(ChannelErrorCode.BINDING_CONFLICT,
                    "target resource identity changed since this dispatch was registered", "targetRef");
                dispatches.noteError(operationRef, error.message());
                return notSent(command, operationRef, error);
            }
            if(!devices.permitsOriginal(resolved.service().serviceRef(),command.createdAt())
                || (resolved.service().accessType()==AccessType.CONNECTOR&&devices.revoked(resolved.service().deviceRef()))){
                dispatches.closeUnsent(command,call);
                return notSent(command,operationRef,ChannelErrorCode.PERMISSION_DENIED.error(
                    "Device access was revoked; this old operation cannot be revived by pairing again"));
            }
            Dispatch row = existing != null ? existing : dispatches.create(command, resolved);

            Prepared prepared;
            BackendProtocol.Authorized authorization;
            try {
                if (command.type() != CommandType.INPUT_SUBMIT && command.type()!=CommandType.INTERACTION_RESPOND
                    && command.type()!=CommandType.SESSION_RESUME) {
                    throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CAPABILITY,
                        "operation is outside personal-text-v1");
                }
                protocol.facts(resolved.service(), resolved.target());
                boolean interaction=command.type()==CommandType.INTERACTION_RESPOND;
                boolean resuming=command.type()==CommandType.SESSION_RESUME;
                if(interaction && !protocol.capabilities(resolved.service()).path("features").path("interaction_response").asBoolean(false))
                    throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CAPABILITY,"Backend interaction response is unavailable");
                if(resuming && !protocol.capabilities(resolved.service()).path("features").path("session_resume").asBoolean(false))
                    throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CAPABILITY,"Backend session recovery is unavailable");
                prepared = interaction?preparedInteraction(command,resolved,row):prepare(command, resolved, row);
                authorization = protocol.authorize(resolved.service(), resolved.target(), call,
                    interaction?"interaction.respond":resuming?"session.resume":"message.submit",
                    "POST", prepared.path(), Json.read(prepared.body()), row.requestKey());
                protocol.bind(row, call, authorization, settings.sinkRef(), receipt(command,
                    new ReceiptPayload.Accepted(ReceiptPayload.AcceptedBy.AGENT)));
                if(interaction)protocol.bindInteraction(row.operationRef(),((CommandPayload.InteractionRespond)command.payload()).payload().interactionRef());
            } catch (IOException e) {
                return notSent(command, operationRef, ChannelErrorCode.UNAVAILABLE.error("backend facts unavailable"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return notSent(command, operationRef, ChannelErrorCode.UNAVAILABLE.error("backend facts interrupted"));
            } catch (ChannelContractException e) {
                ReceiptPacket receipt = receipt(command, new ReceiptPayload.Rejected(e.toError()));
                if(e.code()==ChannelErrorCode.UNAVAILABLE || e.code()==ChannelErrorCode.RATE_LIMITED)
                    return new CommandOutcome(DispatchStatus.NOT_SENT,operationRef,null,e.toError());
                dispatches.recordPreflightRejection(operationRef, receipt, e.getMessage());
                return new CommandOutcome(DispatchStatus.NOT_SENT, operationRef, receipt, e.toError());
            }

            Dispatch claimed = dispatches.claim(operationRef, owner, clock.instant(), lease);
            if (claimed == null) {
                Dispatch fresh = dispatches.load(operationRef);
                if (fresh != null && fresh.phase() == Dispatch.Phase.AGENT_ACCEPTED) {
                    ReceiptPacket stored = dispatches.storedReceipt(fresh);
                    if (stored != null) {
                        return CommandOutcome.accepted(operationRef, stored);
                    }
                }
                return CommandOutcome.unknown(operationRef, ChannelErrorCode.OUTCOME_UNKNOWN.error(
                    "this dispatch is already in flight or its outcome is unknown"));
            }

            PythonBackendClient.Reply reply;
            try {
                reply = client.exchange(resolved.service(), "POST", prepared.path(), prepared.body(), authorization.headers());
            } catch (IOException e) {
                // 网络失败/超时：后端可能已受理，保留 unknown（INV-23、24）。
                ChannelError error = ChannelErrorCode.OUTCOME_UNKNOWN.error(
                    "backend request outcome is unknown; the attempt was persisted before the write");
                dispatches.recordUnknown(operationRef,
                    receipt(command, new ReceiptPayload.Unknown(error)), error.message());
                return CommandOutcome.unknown(operationRef, error);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ChannelError error = ChannelErrorCode.OUTCOME_UNKNOWN.error(
                    "backend request was interrupted; outcome is unknown");
                dispatches.recordUnknown(operationRef,
                    receipt(command, new ReceiptPayload.Unknown(error)), error.message());
                return CommandOutcome.unknown(operationRef, error);
            } catch (ChannelContractException e) {
                ChannelError error = ChannelErrorCode.OUTCOME_UNKNOWN.error(
                    "request could not be completed; outcome is unknown");
                dispatches.recordUnknown(operationRef,
                    receipt(command, new ReceiptPayload.Unknown(error)), e.getMessage());
                return CommandOutcome.unknown(operationRef, error);
            }
            if(command.type()==CommandType.INTERACTION_RESPOND && reply.success()) {
                try {
                    var result=protocol.interactionResult(Json.read(reply.body()),claimed,call,dispatches);
                    return new CommandOutcome(result.status(),operationRef,result.receipt(),result.error());
                } catch(RuntimeException invalidReply) {
                    var error=ChannelErrorCode.OUTCOME_UNKNOWN.error("Backend did not provide bound decision evidence; query the original request");
                    dispatches.recordUnknown(operationRef,receipt(command,new ReceiptPayload.Unknown(error)),error.message());
                    return CommandOutcome.unknown(operationRef,error);
                }
            }
            if(command.type()==CommandType.SESSION_RESUME && reply.success()) {
                try {
                    var result=protocol.resumeResult(Json.read(reply.body()),claimed,call,dispatches);
                    if(result.status()==DispatchStatus.OUTCOME_UNKNOWN) {
                        var error=ChannelErrorCode.OUTCOME_UNKNOWN.error("Resume outcome is unknown; query the original request");
                        dispatches.recordUnknown(operationRef,receipt(command,new ReceiptPayload.Unknown(error)),error.message());
                        return CommandOutcome.unknown(operationRef,error);
                    }
                    return new CommandOutcome(result.status(),operationRef,result.receipt(),result.error());
                } catch(RuntimeException invalidReply) {
                    var error=ChannelErrorCode.OUTCOME_UNKNOWN.error("Backend did not confirm the original resume request");
                    dispatches.recordUnknown(operationRef,receipt(command,new ReceiptPayload.Unknown(error)),error.message());
                    return CommandOutcome.unknown(operationRef,error);
                }
            }
            return interpret(command, claimed, reply);
        } catch (ChannelContractException e) {
            return notSent(command, command.context().operationRef(), e.toError());
        } catch (RuntimeException e) {
            // 未知内部故障：写入可能已发生，保守保留 unknown，不伪造未发送（INV-67）。
            return CommandOutcome.unknown(operationRef,
                ChannelErrorCode.INTERNAL_ERROR.error("internal dispatch failure; outcome is unknown"));
        }
    }

    /** 后端应答 → 派发结果与业务回执。 */
    private CommandOutcome interpret(CommandPacket command, Dispatch row, PythonBackendClient.Reply reply) {
        String operationRef = row.operationRef();
        int status = reply.status();
        if (reply.success()) {
            return switch (command.type()) {
                case INTERACTION_RESPOND -> {
                    if (!provesApplied(reply)) {
                        // 受理不等于本次响应已生效：不得表达 applied（INV-61、62）。
                        ChannelError error = ChannelErrorCode.OUTCOME_UNKNOWN.error(
                            "backend did not confirm that this response was applied");
                        dispatches.recordUnknown(operationRef,
                            receipt(command, new ReceiptPayload.Unknown(error)), error.message());
                        yield CommandOutcome.unknown(operationRef, error);
                    }
                    ReceiptPacket receipt = receipt(command, new ReceiptPayload.Completed(
                        new OperationResult.InteractionRespond(
                            ((CommandPayload.InteractionRespond) command.payload()).payload().interactionRef())));
                    dispatches.recordAccepted(operationRef, receipt);
                    yield CommandOutcome.accepted(operationRef, receipt);
                }
                case TASK_CANCEL -> {
                    // 受理不等于已取消：仅当响应体明确给出状态时才采用，否则 UNKNOWN（INV-24）。
                    TaskStatus taskStatus = TaskStatus.parse(field(reply, "status"));
                    ReceiptPacket receipt = receipt(command, new ReceiptPayload.Completed(
                        new OperationResult.TaskCancel(command.context().taskRef(), taskStatus)));
                    dispatches.recordAccepted(operationRef, receipt);
                    yield CommandOutcome.accepted(operationRef, receipt);
                }
                default -> {
                    if (BackendProtocol.accepted(reply, row.requestKey())) {
                        ReceiptPacket receipt = receipt(command,
                            new ReceiptPayload.Accepted(ReceiptPayload.AcceptedBy.AGENT));
                        dispatches.recordAccepted(operationRef, receipt);
                        yield CommandOutcome.accepted(operationRef, receipt);
                    }
                    ChannelError error = ChannelErrorCode.OUTCOME_UNKNOWN.error(
                        "backend answered without a confirmable submit receipt");
                    dispatches.recordUnknown(operationRef,
                        receipt(command, new ReceiptPayload.Unknown(error)), error.message());
                    yield CommandOutcome.unknown(operationRef, error);
                }
            };
        }
        if (status >= 400 && status < 500 && BackendProtocol.definiteRejection(reply, row.requestKey())) {
            ChannelError error = BackendProtocol.rejectionError(Json.obj(Json.read(reply.body()), "error"));
            if (error.retryAfterMs() == null && reply.retryAfterMs() != null) error = error.withRetryAfterMs(reply.retryAfterMs());
            ReceiptPacket receipt = receipt(command, new ReceiptPayload.Rejected(error));
            dispatches.recordRejected(operationRef, receipt, error.message());
            return new CommandOutcome(DispatchStatus.NOT_SENT, operationRef, receipt, error);
        }
        ChannelError error = ChannelErrorCode.OUTCOME_UNKNOWN.error(
            "backend failed with HTTP " + status + "; outcome is unknown");
        dispatches.recordUnknown(operationRef, receipt(command, new ReceiptPayload.Unknown(error)), error.message());
        return CommandOutcome.unknown(operationRef, error);
    }

    // ---- query ----------------------------------------------------------------------

    @Override
    public CompletionStage<com.netlivecowork.channel.contract.SessionHistory.BackendPage> queryHistory(
            TrustedCall call, String targetRef, PageRequest page) {
        return CompletableFuture.supplyAsync(()->{
            try {
                var resolved=resolver.resolve(targetRef,call.tenantRef(),call.workspaceRef());
                if(!protocol.capabilities(resolved.service()).path("features").path("history_read").asBoolean(false))
                    throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CAPABILITY,"Backend does not support history queries");
                protocol.facts(resolved.service(),resolved.target());
                String path=BackendProtocol.sessionPath(resolved.target().sessionId())+"/history/query";
                var body=Json.obj().put("limit",page.effectiveLimit());
                if(page.cursor()!=null)body.put("cursor",page.cursor());
                var auth=protocol.authorize(resolved.service(),resolved.target(),call,"history.read","POST",path,body,null);
                var reply=client.exchange(resolved.service(),"POST",path,Json.write(body),auth.headers());
                if(!reply.success())throw new ChannelContractException(PythonBackendClient.statusCode(reply.status()),
                    "History query was rejected by the backend (HTTP "+reply.status()+")");
                var result=Json.read(reply.body());
                Json.onlyFields(result,"session_id","resource_epoch","snapshot_revision","items","next_cursor");
                if(!resolved.target().sessionId().equals(Json.text(result,"session_id"))
                    || Json.reqLong(result,"resource_epoch")!=resolved.target().resourceEpoch()
                    || Json.reqLong(result,"snapshot_revision")<1 || !result.path("items").isArray()
                    || result.path("items").size()>page.effectiveLimit())
                    throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE,"Invalid history resource or page");
                var items=new java.util.ArrayList<com.netlivecowork.channel.contract.SessionHistory.BackendMessage>();
                var seen=new java.util.HashSet<String>();
                for(var item:result.path("items")) {
                    Json.onlyFields(item,"message_id","role","text","unsupported_content","created_at");
                    String id=Json.text(item,"message_id");
                    if(!seen.add(id) || !item.path("unsupported_content").isBoolean())
                        throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE,"Invalid history message identity");
                    items.add(new com.netlivecowork.channel.contract.SessionHistory.BackendMessage(id,Json.text(item,"role"),
                        item.path("text").isTextual()?item.path("text").textValue():null,item.path("unsupported_content").asBoolean(),
                        java.time.Instant.parse(Json.text(item,"created_at"))));
                }
                return new com.netlivecowork.channel.contract.SessionHistory.BackendPage(items,Json.optText(result,"next_cursor"));
            } catch(ChannelContractException e) { throw e; }
            catch(InterruptedException e) { Thread.currentThread().interrupt();throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE,"History query interrupted"); }
            catch(Exception e) { throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE,"History query is unavailable"); }
        },executor);
    }

    @Override
    public CompletionStage<ReceiptPacket> query(CommandPacket command, TrustedCall call) {
        return CompletableFuture.supplyAsync(() -> queryNow(command, call), executor);
    }

    private ReceiptPacket queryNow(CommandPacket command, TrustedCall call) {
        try {
            if (command.type() == CommandType.SESSION_LIST) {
                return listSessions(command, call);
            }
            if(expired(call))return rejected(command,ChannelErrorCode.UNAVAILABLE.error("call deadline has already passed"));
            TargetResolver.Resolved resolved = resolver.resolve(command.context().targetRef(),
                call.tenantRef(), call.workspaceRef());
            return switch (command.type()) {
                case SESSION_GET -> sessionGet(command, resolved, call);
                case TASK_GET -> rejected(command, ChannelErrorCode.UNSUPPORTED_CAPABILITY.error(
                    "the v1 backend exposes no stable task identity/status query for Channel taskRefs"));
                default -> rejected(command, ChannelErrorCode.INVALID_ARGUMENT.error(
                    command.type().wire() + " is not a backend read query"));
            };
        } catch (ChannelContractException e) {
            return rejected(command, e.toError());
        } catch (IOException e) {
            ChannelError error = ChannelErrorCode.OUTCOME_UNKNOWN.error("backend query outcome is unknown");
            return receipt(command, new ReceiptPayload.Unknown(error));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ChannelError error = ChannelErrorCode.OUTCOME_UNKNOWN.error("backend query was interrupted");
            return receipt(command, new ReceiptPayload.Unknown(error));
        } catch (RuntimeException e) {
            return rejected(command, ChannelErrorCode.INTERNAL_ERROR.error("internal query failure"));
        }
    }

    private ReceiptPacket listSessions(CommandPacket command, TrustedCall call) {
        PageRequest page = ((CommandPayload.SessionList) command.payload()).page();
        List<TargetSummary> targets = registry.visibleTargets(call.tenantRef(), call.workspaceRef(),
            call.principalRef(), ((CommandPayload.SessionList) command.payload()).agentRef());
        List<SessionSummary> items = new ArrayList<>(targets.size());
        for (TargetSummary target : targets) {
            items.add(new SessionSummary(target.targetRef(), target.label(), target.agentRef(),
                target.availability() == TargetSummary.Availability.ONLINE));
        }
        int limit = page.effectiveLimit();
        List<SessionSummary> limited = items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
        return receipt(command, new ReceiptPayload.Completed(new OperationResult.SessionList(limited, null)));
    }

    private ReceiptPacket sessionGet(CommandPacket command, TargetResolver.Resolved resolved, TrustedCall call)
        throws IOException, InterruptedException {
        protocol.facts(resolved.service(), resolved.target());
        String path = BackendProtocol.sessionPath(resolved.target().sessionId());
        var auth = protocol.authorize(resolved.service(), resolved.target(), call, "session.read", "GET", path,
            Json.obj(), null);
        java.util.Map<String,String> headers = new java.util.HashMap<>(auth.headers());
        var features = protocol.capabilities(resolved.service()).path("features");
        boolean inputSemantics = features.path("input_semantics").asBoolean(false);
        boolean workspaces = features.path("session_workspaces").asBoolean(false);
        var offered = new java.util.ArrayList<String>();
        if (inputSemantics) offered.add("input-semantics-v1");
        if (workspaces) offered.add("session-workspaces-v1");
        if (!offered.isEmpty()) headers.put("X-Channel-Features", String.join(",", offered));
        PythonBackendClient.Reply reply = readWithRetry(() -> client.exchange(resolved.service(), "GET", path, null,
            headers));
        if (reply.success()) {
            JsonNode snapshot = Json.read(reply.body());
            if (!inputSemantics && (snapshot.has("input") || snapshot.has("raw_status")))
                throw new IllegalArgumentException("unnegotiated backend input facts");
            if (inputSemantics && (!snapshot.has("input") || !snapshot.has("raw_status")))
                throw new IllegalArgumentException("backend omitted negotiated input facts");
            if (snapshot.has("workspace") != workspaces)
                throw new IllegalArgumentException("backend workspace negotiation mismatch");
            com.netlivecowork.channel.contract.SessionWorkspace workspace = null;
            if (workspaces) {
                if (!resolved.target().sessionId().equals(Json.text(snapshot,"session_id"))
                    || resolved.target().resourceEpoch()!=Json.reqLong(snapshot,"resource_epoch"))
                    throw new IllegalArgumentException("workspace metadata belongs to a different resource");
                var value = Json.obj(snapshot,"workspace");
                Json.onlyFields(value,"workspace_id","label");
                String id = Json.text(value,"workspace_id");
                workspace = protocol.workspace(resolved.service(),id,Json.text(value,"label"),false);
            }
            String label = field(reply, "title");
            SessionSummary summary = new SessionSummary(resolved.target().targetRef(),
                label == null ? resolved.target().label() : label, resolved.target().agentRef(), true,
                BackendProtocol.sessionExecution(Json.read(reply.body())), workspace);
            return receipt(command, new ReceiptPayload.Completed(new OperationResult.SessionGet(summary)));
        }
        if (reply.status() >= 400 && reply.status() < 500) {
            return rejected(command, clientError(command.type(), reply));
        }
        ChannelError error = ChannelErrorCode.OUTCOME_UNKNOWN.error(
            "backend session query failed with HTTP " + reply.status());
        return receipt(command, new ReceiptPayload.Unknown(error));
    }

    @Override
    public CompletionStage<java.util.List<VerifiedBackendEvent>> readInteractionEvents(String targetRef,TrustedCall call) {
        return CompletableFuture.supplyAsync(()-> {
            try {
                var resolved=resolver.resolve(targetRef,call.tenantRef(),call.workspaceRef());
                if(!protocol.capabilities(resolved.service()).path("features").path("interaction_response").asBoolean(false))
                    throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CAPABILITY,"Backend interactions are unavailable");
                var path=BackendProtocol.sessionPath(resolved.target().sessionId())+"/interactions";
                var auth=protocol.authorize(resolved.service(),resolved.target(),call,"session.read","GET",path,Json.obj(),null);
                var reply=client.exchange(resolved.service(),"GET",path,null,auth.headers());
                if(!reply.success())throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE,"Interaction recovery failed");
                var body=Json.read(reply.body());Json.onlyFields(body,"session_id","resource_epoch","events");
                if(!resolved.target().sessionId().equals(Json.text(body,"session_id")) || resolved.target().resourceEpoch()!=Json.reqLong(body,"resource_epoch"))
                    throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Interaction snapshot resource mismatch");
                var result=new java.util.ArrayList<VerifiedBackendEvent>();
                for(var raw:Json.array(body,"events"))result.add(interactionEvents.acceptSnapshot(resolved.target(),BackendEventCodec.decodeSnapshot(Json.write(raw)),Json.write(raw)));
                return java.util.List.copyOf(result);
            } catch(java.io.IOException e) {throw new IllegalStateException("Interaction recovery failed",e);}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
        },executor);
    }

    // ---- lookup ---------------------------------------------------------------------

    @Override
    public CompletionStage<OperationLookup> lookupOperation(String operationRef, TrustedCall call) {
        return CompletableFuture.supplyAsync(() -> lookupNow(operationRef, call), executor);
    }

    private OperationLookup lookupNow(String operationRef, TrustedCall call) {
        if(dispatches.closed(operationRef,call))return new OperationLookup(operationRef,DispatchStatus.NOT_SENT,null,
            ChannelErrorCode.UNAVAILABLE.error("Original operation was durably closed without submission"));
        Dispatch row = dispatches.load(operationRef);
        if (row == null) {
            // 本机查无记录不是未发送证明（INV-67）：只报告未知，不伪造 NOT_SENT。
            return new OperationLookup(operationRef, DispatchStatus.OUTCOME_UNKNOWN, null,
                ChannelErrorCode.REQUEST_NOT_FOUND.error("no dispatch record for operationRef"));
        }
        TargetRegistration target = registry.target(row.targetRef());
        if (target != null && (!target.tenantRef().equals(call.tenantRef())
            || !target.workspaceRef().equals(call.workspaceRef()))) {
            return new OperationLookup(operationRef, DispatchStatus.OUTCOME_UNKNOWN, null,
                ChannelErrorCode.PERMISSION_DENIED.error("trusted call scope does not own this dispatch"));
        }
        if (row.phase() == Dispatch.Phase.SENT_UNKNOWN) {
            try {
                if(row.commandType()==CommandType.INTERACTION_RESPOND)return protocol.lookupInteraction(expectedService(row.serviceRef()),target,row,call,dispatches);
                if(row.commandType()==CommandType.SESSION_RESUME)return protocol.lookupResume(expectedService(row.serviceRef()),target,row,call,dispatches);
                return protocol.lookup(expectedService(row.serviceRef()), row, call, dispatches);
            }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return BackendProtocol.unknown(operationRef); }
            catch (IOException | RuntimeException e) { return BackendProtocol.unknown(operationRef); }
        }
        ReceiptPacket receipt = dispatches.storedReceipt(row);
        DispatchStatus status = switch (row.phase()) {
            case AGENT_ACCEPTED -> DispatchStatus.AGENT_ACCEPTED;
            case NOT_SENT -> DispatchStatus.NOT_SENT;
            case SENT_UNKNOWN, REGISTERED -> DispatchStatus.OUTCOME_UNKNOWN;
        };
        ChannelError error = row.lastError() == null ? null : ChannelError.of(
            row.phase() == Dispatch.Phase.NOT_SENT ? ChannelErrorCode.INVALID_ARGUMENT
                : ChannelErrorCode.OUTCOME_UNKNOWN, row.lastError());
        if (receipt != null && receipt.payload() instanceof ReceiptPayload.Rejected rejected) error = rejected.error();
        else if (receipt != null && receipt.payload() instanceof ReceiptPayload.Unknown unknown) error = unknown.error();
        return new OperationLookup(operationRef, status, receipt, error);
    }

    // ---- targets --------------------------------------------------------------------

    @Override
    public CompletionStage<List<TargetSummary>> listTargets(TrustedCall call, String agentRef, PageRequest page) {
        return CompletableFuture.supplyAsync(() -> {
            List<TargetSummary> targets = registry.visibleTargets(call.tenantRef(), call.workspaceRef(),
                call.principalRef(), agentRef);
            Integer limit = page == null ? null : page.limit();
            if (limit == null || targets.size() <= limit) {
                return targets;
            }
            return List.copyOf(targets.subList(0, limit));
        }, executor);
    }

    @Override
    public CompletionStage<List<com.netlivecowork.channel.contract.TargetOwnership>> targetOwnerships(
        TrustedCall call) {
        return CompletableFuture.supplyAsync(() -> {
            var facts = new ArrayList<com.netlivecowork.channel.contract.TargetOwnership>();
            for (var summary : registry.visibleTargets(call.tenantRef(), call.workspaceRef(), call.principalRef(), null)) {
                var target = registry.target(summary.targetRef());
                try { facts.add(protocol.facts(expectedService(target.serviceRef()), target)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                catch (IOException | RuntimeException e) { /* Unverified resources never grant authority. */ }
            }
            return List.copyOf(facts);
        }, executor);
    }

    @Override
    public CompletionStage<com.netlivecowork.channel.contract.SessionAccess.Manager> verifySessionManager(String serviceRef,String protectedProof) {
        return CompletableFuture.supplyAsync(()->{
            try{return protocol.sessionManager(registry.service(serviceRef),protectedProof);}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new com.netlivecowork.channel.contract.ChannelContractException(ChannelErrorCode.UNAVAILABLE,"CoWork authentication interrupted");}
            catch(IOException e){throw new com.netlivecowork.channel.contract.ChannelContractException(ChannelErrorCode.UNAVAILABLE,"CoWork authentication unavailable");}
        },executor);
    }

    @Override
    public CompletionStage<com.netlivecowork.channel.contract.SessionAccess.Catalog> discoverOwnedSessions(TrustedCall call) {
        return CompletableFuture.supplyAsync(()->{
            try {
                catalogs.entrySet().removeIf(e->e.getValue().catalog().verifiedAt().isBefore(clock.instant().minusSeconds(30)));
                if(catalogs.size()>=100) throw new com.netlivecowork.channel.contract.ChannelContractException(ChannelErrorCode.UNAVAILABLE,"Too many concurrent catalog requests");
                var discovery=protocol.discoverOwnedSessions(registry,call);
                catalogs.put(discovery.catalog().authenticationRef(),discovery);
                return discovery.catalog();
            }
            catch(InterruptedException e) { Thread.currentThread().interrupt();throw new com.netlivecowork.channel.contract.ChannelContractException(ChannelErrorCode.UNAVAILABLE,"Catalog query interrupted"); }
            catch(IOException e) { throw new com.netlivecowork.channel.contract.ChannelContractException(ChannelErrorCode.UNAVAILABLE,"Authenticated backend catalog is unavailable"); }
        },executor);
    }

    @Override
    public CompletionStage<Void> registerCatalogTargets(TrustedCall call,String authenticationRef) {
        return CompletableFuture.runAsync(()->{
            var discovery=catalogs.get(authenticationRef);
            if(discovery==null || discovery.catalog().verifiedAt().isBefore(clock.instant().minusSeconds(30))
                || !discovery.call().tenantRef().equals(call.tenantRef()) || !discovery.call().workspaceRef().equals(call.workspaceRef())
                || !discovery.call().principalRef().equals(call.principalRef()))
                throw new com.netlivecowork.channel.contract.ChannelContractException(ChannelErrorCode.AUTH_REQUIRED,"Verified catalog has expired or belongs to another caller");
            discovery.registrations().forEach(registry::upsertTarget);
            catalogs.remove(authenticationRef,discovery);
        },executor);
    }

    // ---- session watch --------------------------------------------------------------

    @Override
    public CompletionStage<SessionWatch> ensureSessionWatch(SessionWatchRequest request) {
        return CompletableFuture.supplyAsync(() -> ensureWatch(request), executor);
    }

    private SessionWatch ensureWatch(SessionWatchRequest request) {
        if (!settings.sessionWatchSupported()) {
            return SessionWatch.unsupported(ChannelErrorCode.UNSUPPORTED_CAPABILITY.error(
                "session-level event watch is not supported by this deployment"));
        }
        TargetResolver.Resolved resolved = resolver.resolve(request.targetRef(), request.tenantRef(),
            request.workspaceRef());
        SessionWatchStore.Row existing = watches.find(request.requestKey());
        if (existing != null && !existing.sameParameters(request, resolved.target().sessionId())) {
            throw new ChannelContractException(ChannelErrorCode.REQUEST_CONFLICT,
                "watch requestKey was first registered with different parameters");
        }
        if (existing != null && existing.watchRef() != null) {
            return SessionWatch.active(existing.watchRef(), existing.startBoundary());
        }
        if (existing == null) {
            watches.savePending(request, resolved.target().targetRef(), resolved.service().serviceRef(),
                resolved.target().sessionId());
        }
        ObjectNode body = Json.obj()
            .put("request_key", request.requestKey())
            .put("event_sink_ref", request.sinkRef());
        var eventTypes = body.putArray("event_types");
        SessionWatchStore.Row.wireTypes(request.eventTypes()).forEach(eventTypes::add);
        if (request.resumeCursor() != null) {
            body.put("resume_cursor", request.resumeCursor());
        }
        PythonBackendClient.Reply reply;
        try {
            reply = client.post(resolved.service(), PythonBackendClient.fill(settings.endpoints().watch(),
                "sessionId", resolved.target().sessionId()), Json.write(body));
        } catch (IOException e) {
            // 登记结果未知：调用方按 pending 记录，用原请求键恢复（INV-65）。
            throw new ChannelContractException(ChannelErrorCode.OUTCOME_UNKNOWN,
                "session watch registration outcome is unknown");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelContractException(ChannelErrorCode.OUTCOME_UNKNOWN,
                "session watch registration was interrupted");
        }
        if (reply.success()) {
            String watchRef = firstField(reply, "watch_ref", "watchRef", "watch_id");
            if (watchRef == null) {
                throw new ChannelContractException(ChannelErrorCode.OUTCOME_UNKNOWN,
                    "backend accepted the watch without a registration reference");
            }
            String boundary = firstField(reply, "start_boundary", "startBoundary", "cursor");
            watches.recordActive(request.requestKey(), watchRef, boundary);
            return SessionWatch.active(watchRef, boundary);
        }
        if (reply.status() >= 400 && reply.status() < 500) {
            watches.recordUnsupported(request.requestKey());
            return SessionWatch.unsupported(ChannelErrorCode.UNSUPPORTED_CAPABILITY.error(
                "backend rejected session watch registration with HTTP " + reply.status()));
        }
        throw new ChannelContractException(ChannelErrorCode.OUTCOME_UNKNOWN,
            "backend watch registration failed with HTTP " + reply.status());
    }

    @Override
    public CompletionStage<Void> removeSessionWatch(String watchRef) {
        return CompletableFuture.supplyAsync(() -> {
            SessionWatchStore.Row row = watches.findByWatchRef(watchRef);
            if (row == null) {
                return null;
            }
            BackendService service = expectedService(row.serviceRef());
            try {
                PythonBackendClient.Reply reply = client.delete(service,
                    PythonBackendClient.fill(settings.endpoints().watchRemove(), "sessionId", row.sessionId(),
                        "watchRef", watchRef));
                if (!reply.success() && reply.status() != 404) {
                    throw new ChannelContractException(ChannelErrorCode.OUTCOME_UNKNOWN,
                        "backend watch removal failed with HTTP " + reply.status());
                }
            } catch (IOException e) {
                throw new ChannelContractException(ChannelErrorCode.OUTCOME_UNKNOWN,
                    "backend watch removal outcome is unknown");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ChannelContractException(ChannelErrorCode.OUTCOME_UNKNOWN,
                    "backend watch removal was interrupted");
            }
            watches.remove(row.requestKey());
            return null;
        }, executor);
    }

    // ---- files ----------------------------------------------------------------------

    @Override
    public CompletionStage<FileReadResult> readFile(String fileRef, String deliveryId) {
        if (!settings.filesSupported()) {
            return CompletableFuture.failedFuture(new ChannelContractException(
                ChannelErrorCode.UNSUPPORTED_CAPABILITY, "backend file reading is not supported by this deployment"));
        }
        return CompletableFuture.supplyAsync(() -> readFileNow(fileRef, deliveryId), executor);
    }

    private FileReadResult readFileNow(String fileRef, String deliveryId) {
        // deliveryId 只用于投递侧审计；定位只用 L3 的不透明 fileRef 关联（INV-45）。
        BackendRegistry.FileLink link = registry.file(fileRef);
        if (link == null) {
            throw new ChannelContractException(ChannelErrorCode.REQUEST_NOT_FOUND,
                "fileRef is not registered with a backend file association");
        }
        BackendService service = expectedService(link.serviceRef());
        try {
            return client.readFile(service, link.backendFileId(), link.metadata());
        } catch (IOException e) {
            throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE, "backend file read failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelContractException(ChannelErrorCode.OUTCOME_UNKNOWN, "backend file read was interrupted");
        }
    }

    // ---- helpers --------------------------------------------------------------------

    private record Prepared(String path, String body) {
    }

    private BackendService expectedService(String serviceRef) {
        BackendService service = registry.service(serviceRef);
        if (service == null) {
            throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE, "backend service is not registered");
        }
        if (!service.enabled()) {
            throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE, "backend service is disabled");
        }
        return service;
    }

    /** 构造后端写请求（不含任何平台标识）；能力/内容不足时在派发前明确拒绝。 */
    private Prepared prepare(CommandPacket command, TargetResolver.Resolved resolved, Dispatch row) {
        return switch (command.type()) {
            case SESSION_RESUME -> {
                ObjectNode body=Json.obj().put("request_key",row.requestKey());
                yield new Prepared(BackendProtocol.sessionPath(resolved.target().sessionId())+"/resume",Json.write(body));
            }
            case INPUT_SUBMIT -> {
                String sinkRef = settings.sinkRef();
                if (sinkRef == null) {
                    throw new ChannelContractException(ChannelErrorCode.INTERNAL_ERROR,
                        "event sink ref is not configured for backend submission");
                }
                ObjectNode body = Json.obj().put("request_key", row.requestKey());
                if (resolved.target().agentRef() != null) {
                    body.put("agent_id", resolved.target().agentRef());
                }
                body.put("content", textContent((CommandPayload.InputSubmit) command.payload()));
                body.put("event_sink_ref", sinkRef);
                yield new Prepared(PythonBackendClient.fill(settings.endpoints().submit(), "sessionId",
                    resolved.target().sessionId()), Json.write(body));
            }
            case TASK_CANCEL -> {
                String taskRef = command.context().taskRef();
                if (taskRef == null) {
                    throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                        "task.cancel requires context.taskRef");
                }
                if (!resolved.target().supports("task.cancel")) {
                    // 现有后端 interrupt 是软暂停，不能冒充真实取消（INV-24）。
                    throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CAPABILITY,
                        "target does not support real task cancellation");
                }
                ObjectNode body = Json.obj().put("request_key", row.requestKey()).put("task_ref", taskRef);
                String reason = ((CommandPayload.TaskCancel) command.payload()).reason();
                if (reason != null) {
                    body.put("reason", reason);
                }
                yield new Prepared(PythonBackendClient.fill(settings.endpoints().taskCancel(), "sessionId",
                    resolved.target().sessionId(), "taskRef", taskRef), Json.write(body));
            }
            case INTERACTION_RESPOND -> interactionRequest(command, resolved);
            default -> throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                command.type().wire() + " is not a backend write command");
        };
    }

    private Prepared interactionRequest(CommandPacket command, TargetResolver.Resolved resolved) {
        CommandPayload.InteractionRespond respond = (CommandPayload.InteractionRespond) command.payload();
        InteractionStore.Mapping mapping = interactions.find(respond.payload().interactionRef());
        if (mapping == null) {
            throw new ChannelContractException(ChannelErrorCode.INTERACTION_STALE,
                "interactionRef is not mapped to a backend interaction");
        }
        if (!mapping.targetRef().equals(resolved.target().targetRef())) {
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,
                "interactionRef belongs to a different target");
        }
        if (mapping.revision() != respond.payload().expectedRevision()) {
            // 事项版本已变：旧动作失效，不把旧响应当成本次响应（INV-59、62）。
            throw new ChannelContractException(ChannelErrorCode.INTERACTION_STALE,
                "expectedRevision does not match the registered interaction revision");
        }
        ObjectNode body = Json.obj();
        String action = switch (respond.payload().response()) {
            case InteractionResponse.Approve approve -> {
                if (approve.modifiedArguments() != null) {
                    body.set("modify", approve.modifiedArguments());
                }
                yield "approve";
            }
            case InteractionResponse.Reject reject -> {
                if (reject.reason() != null) {
                    body.put("message", reject.reason());
                }
                yield "reject";
            }
            case InteractionResponse.Answer answer -> {
                body.put("answer", flattenAnswer(answer));
                yield "answer";
            }
        };
        String path = PythonBackendClient.fill(settings.endpoints().interaction(), "interactionId",
            mapping.backendInteractionId(), "action", action)
            + "?session_id=" + PythonBackendClient.encode(resolved.target().sessionId());
        return new Prepared(path, Json.write(body));
    }

    private Prepared preparedInteraction(CommandPacket command,TargetResolver.Resolved resolved,Dispatch row) {
        var response=((CommandPayload.InteractionRespond)command.payload()).payload();
        var mapped=interactions.find(response.interactionRef());
        if(mapped==null || mapped.revision()!=response.expectedRevision())throw new ChannelContractException(ChannelErrorCode.INTERACTION_STALE,"Interaction mapping changed");
        if(!mapped.targetRef().equals(resolved.target().targetRef()))throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Interaction belongs to another target");
        var body=Json.obj().put("request_key",row.requestKey()).put("expected_revision",response.expectedRevision()).put("event_sink_ref",settings.sinkRef());
        body.set("response",com.netlivecowork.channel.contract.json.ContentJson.encode(response.response()));
        var path=BackendProtocol.sessionPath(resolved.target().sessionId())+"/interactions/"+PythonBackendClient.encode(mapped.backendInteractionId())+"/responses";
        return new Prepared(path,Json.write(body));
    }

    /**
     * v1 后端 answer 只接受单段纯文本：整组答案被确定性展平（题/选项身份保留在文本中）。
     * 这是明确的能力缺口，不是无损映射；多题与选项 ID 的结构化提交待后端契约补齐（INV-59、60）。
     */
    private static String flattenAnswer(InteractionResponse.Answer answer) {
        StringBuilder text = new StringBuilder();
        for (QuestionAnswer item : answer.answers()) {
            StringBuilder line = new StringBuilder();
            switch (item) {
                case QuestionAnswer.TextAnswer textAnswer -> {
                    for (ContentPart part : textAnswer.content()) {
                        if (!(part instanceof ContentPart.Text value)) {
                            throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CONTENT,
                                "backend answer accepts text only");
                        }
                        line.append(value.text());
                    }
                }
                case QuestionAnswer.ChoiceAnswer choice -> {
                    if (!choice.optionIds().isEmpty()) {
                        line.append(String.join(", ", choice.optionIds()));
                    }
                    if (choice.otherText() != null) {
                        if (!line.isEmpty()) {
                            line.append(" / ");
                        }
                        line.append(choice.otherText());
                    }
                }
            }
            if (line.isEmpty()) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "answer for " + item.questionId() + " is empty");
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(line);
        }
        return text.toString();
    }

    /** 纯文本提交内容；v1 专用接入只接受文本，附件/引用能力未启用时明确拒绝（INV-38、41）。 */
    private static String textContent(CommandPayload.InputSubmit submit) {
        if (submit.message().replyToMessageRef() != null) {
            throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CAPABILITY,
                "reply_to_message_id is not enabled for this backend");
        }
        StringBuilder text = new StringBuilder();
        for (ContentPart part : submit.message().content()) {
            switch (part) {
                case ContentPart.Text value -> {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(value.text());
                }
                case ContentPart.MessageReference ignored ->
                    throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CAPABILITY,
                        "message reference capability is not enabled for this backend");
                case ContentPart.Image ignored -> throw new ChannelContractException(
                    ChannelErrorCode.UNSUPPORTED_CONTENT, "backend submit accepts text content only");
                case ContentPart.FilePart ignored -> throw new ChannelContractException(
                    ChannelErrorCode.UNSUPPORTED_CONTENT, "backend submit accepts text content only");
            }
        }
        if (text.toString().isBlank() || text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "input.submit requires nonblank text within 64 KiB");
        }
        return text.toString();
    }

    private static void requireWriteType(CommandType type) {
        if (!type.requiresBackend() || type == CommandType.SESSION_LIST || type == CommandType.SESSION_GET
            || type == CommandType.TASK_GET) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                type.wire() + " is not a backend write command");
        }
    }

    private boolean expired(TrustedCall call) {
        Instant deadline = call.deadline();
        return deadline != null && clock.instant().isAfter(deadline);
    }

    private ReceiptPacket receipt(CommandPacket command, ReceiptPayload payload) {
        return new ReceiptPacket("1", Ids.next("receipt"), clock.instant(),
            new Correlation(command.correlation().flowId(), command.packetId()), command.context(), PRODUCER,
            payload);
    }

    private CommandOutcome notSent(CommandPacket command, String operationRef, ChannelError error) {
        boolean transientFailure=error.errorCode()==ChannelErrorCode.UNAVAILABLE || error.errorCode()==ChannelErrorCode.RATE_LIMITED;
        return new CommandOutcome(DispatchStatus.NOT_SENT, operationRef, transientFailure?null:rejected(command, error), error);
    }

    private ReceiptPacket rejected(CommandPacket command, ChannelError error) {
        return receipt(command, new ReceiptPayload.Rejected(error));
    }

    private ChannelError clientError(CommandType type, PythonBackendClient.Reply reply) {
        ChannelErrorCode code = switch (reply.status()) {
            case 401 -> ChannelErrorCode.AUTH_REQUIRED;
            case 403 -> ChannelErrorCode.PERMISSION_DENIED;
            case 404 -> ChannelErrorCode.REQUEST_NOT_FOUND;
            case 400, 422 -> ChannelErrorCode.INVALID_ARGUMENT;
            case 409 -> type == CommandType.INTERACTION_RESPOND
                ? ChannelErrorCode.INTERACTION_STALE : ChannelErrorCode.REQUEST_CONFLICT;
            case 429 -> ChannelErrorCode.RATE_LIMITED;
            default -> ChannelErrorCode.INVALID_ARGUMENT;
        };
        ChannelError error = ChannelError.of(code, "backend rejected the request with HTTP " + reply.status());
        return reply.retryAfterMs() == null ? error : error.withRetryAfterMs(reply.retryAfterMs());
    }

    private static boolean provesApplied(PythonBackendClient.Reply reply) {
        JsonNode body = parse(reply.body());
        if (body == null) {
            return false;
        }
        if (body.path("applied").asBoolean(false)) {
            return true;
        }
        return "applied".equals(body.path("status").asText(null));
    }

    private static String field(PythonBackendClient.Reply reply, String name) {
        JsonNode body = parse(reply.body());
        if (body == null) {
            return null;
        }
        JsonNode value = body.get(name);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static String firstField(PythonBackendClient.Reply reply, String... names) {
        for (String name : names) {
            String value = field(reply, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return Json.read(body);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private PythonBackendClient.Reply readWithRetry(Read read) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_READ_ATTEMPTS; attempt++) {
            try {
                PythonBackendClient.Reply reply = read.perform();
                if (reply.status() < 500 || attempt == MAX_READ_ATTEMPTS) {
                    return reply;
                }
            } catch (IOException e) {
                last = e;
                if (attempt == MAX_READ_ATTEMPTS) {
                    throw e;
                }
            }
        }
        throw last == null ? new IOException("backend read failed") : last;
    }

    @FunctionalInterface
    private interface Read {
        PythonBackendClient.Reply perform() throws IOException, InterruptedException;
    }

    /** L2 登记内部事项引用到后端事项身份的映射（interaction.respond 的前提）。同步落库。 */
    @Override
    public CompletionStage<Void> registerInteraction(String interactionRef, String targetRef,
                                                     String backendInteractionId, long revision) {
        interactions.register(interactionRef, targetRef, backendInteractionId, revision);
        return CompletableFuture.completedFuture(null);
    }

    /** L2 登记 fileRef 到后端文件的不透明关联；读取仍经 readFile。 */
    @Override
    public CompletionStage<Void> registerFile(String fileRef, String targetRef, String backendFileId,
                                              com.netlivecowork.channel.contract.FileMetadata metadata) {
        registry.registerFile(fileRef, targetRef, backendFileId, metadata);
        return CompletableFuture.completedFuture(null);
    }
}
