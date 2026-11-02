package com.netlivecowork.channel.contract.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netlivecowork.channel.contract.Actor;
import com.netlivecowork.channel.contract.AgentEvent;
import com.netlivecowork.channel.contract.AgentEventPayload;
import com.netlivecowork.channel.contract.BackendInteraction;
import com.netlivecowork.channel.contract.BackendInteractionRef;
import com.netlivecowork.channel.contract.ChannelDelivery;
import com.netlivecowork.channel.contract.ChannelError;
import com.netlivecowork.channel.contract.ChannelMessage;
import com.netlivecowork.channel.contract.ChannelOperation;
import com.netlivecowork.channel.contract.ChannelResponse;
import com.netlivecowork.channel.contract.CommandPacket;
import com.netlivecowork.channel.contract.CommandPayload;
import com.netlivecowork.channel.contract.CommandType;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.Correlation;
import com.netlivecowork.channel.contract.DeliveryAttempt;
import com.netlivecowork.channel.contract.DeliveryReceipt;
import com.netlivecowork.channel.contract.EventOrder;
import com.netlivecowork.channel.contract.EventPacket;
import com.netlivecowork.channel.contract.EventPayload;
import com.netlivecowork.channel.contract.EventSource;
import com.netlivecowork.channel.contract.EventType;
import com.netlivecowork.channel.contract.IngressRequest;
import com.netlivecowork.channel.contract.InteractionRequiredPayload;
import com.netlivecowork.channel.contract.InteractionRespondPayload;
import com.netlivecowork.channel.contract.Layer;
import com.netlivecowork.channel.contract.OperationResult;
import com.netlivecowork.channel.contract.PageRequest;
import com.netlivecowork.channel.contract.PacketContext;
import com.netlivecowork.channel.contract.Producer;
import com.netlivecowork.channel.contract.ReceiptPacket;
import com.netlivecowork.channel.contract.ReceiptPayload;
import com.netlivecowork.channel.contract.SessionSummary;
import com.netlivecowork.channel.contract.SubscriptionResource;
import com.netlivecowork.channel.contract.TaskSnapshot;
import com.netlivecowork.channel.contract.TaskStatus;
import com.netlivecowork.channel.contract.VerifiedBackendEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** 公共包的 JSON 编解码：持久化与 HTTP 传输共用同一格式，未知类型/字段一律拒绝。 */
public final class PacketJson {

    private PacketJson() {
    }

    // ---- envelope parts --------------------------------------------------------------

    public static ObjectNode enc(Correlation correlation) {
        ObjectNode node = Json.obj().put("flowId", correlation.flowId());
        if (correlation.causationId() != null) {
            node.put("causationId", correlation.causationId());
        }
        return node;
    }

    public static Correlation decCorrelation(JsonNode node) {
        Json.onlyFields(node, "flowId", "causationId");
        return new Correlation(Json.text(node, "flowId"), Json.optText(node, "causationId"));
    }

    public static ObjectNode enc(PacketContext context) {
        ObjectNode node = Json.obj().put("tenantRef", context.tenantRef()).put("workspaceRef", context.workspaceRef());
        if (context.targetRef() != null) {
            node.put("targetRef", context.targetRef());
        }
        if (context.operationRef() != null) {
            node.put("operationRef", context.operationRef());
        }
        if (context.taskRef() != null) {
            node.put("taskRef", context.taskRef());
        }
        return node;
    }

    public static PacketContext decContext(JsonNode node) {
        Json.onlyFields(node, "tenantRef", "workspaceRef", "targetRef", "operationRef", "taskRef");
        return new PacketContext(Json.text(node, "tenantRef"), Json.text(node, "workspaceRef"),
            Json.optText(node, "targetRef"), Json.optText(node, "operationRef"), Json.optText(node, "taskRef"));
    }

    public static ObjectNode enc(Producer producer) {
        return Json.obj().put("layer", producer.layer().name()).put("componentRef", producer.componentRef());
    }

    public static Producer decProducer(JsonNode node) {
        Json.onlyFields(node, "layer", "componentRef");
        Layer layer = Json.enumValue(node, "layer", Layer.class);
        return new Producer(layer, Json.text(node, "componentRef"));
    }

    public static ObjectNode enc(Actor actor) {
        ObjectNode node = Json.obj().put("principalRef", actor.principalRef());
        if (actor.delegationRef() != null) {
            node.put("delegationRef", actor.delegationRef());
        }
        return node;
    }

    public static Actor decActor(JsonNode node) {
        Json.onlyFields(node, "principalRef", "delegationRef");
        return new Actor(Json.text(node, "principalRef"), Json.optText(node, "delegationRef"));
    }

    public static ObjectNode enc(EventSource source) {
        return Json.obj().put("resourceRef", source.resourceRef()).put("sourceEventId", source.sourceEventId());
    }

    public static EventSource decSource(JsonNode node) {
        Json.onlyFields(node, "resourceRef", "sourceEventId");
        return new EventSource(Json.text(node, "resourceRef"), Json.text(node, "sourceEventId"));
    }

    public static ObjectNode enc(EventOrder order) {
        return Json.obj().put("scopeRef", order.scopeRef()).put("sequence", order.sequence());
    }

    public static EventOrder decOrder(JsonNode node) {
        Json.onlyFields(node, "scopeRef", "sequence");
        return new EventOrder(Json.text(node, "scopeRef"), Json.reqLong(node, "sequence"));
    }

    public static ObjectNode enc(ChannelError error) {
        ObjectNode node = Json.obj().put("code", error.code()).put("message", error.message())
            .put("retryable", error.retryable());
        if (error.field() != null) {
            node.put("field", error.field());
        }
        if (error.retryAfterMs() != null) {
            node.put("retryAfterMs", error.retryAfterMs());
        }
        return node;
    }

    public static ChannelError decError(JsonNode node) {
        Json.onlyFields(node, "code", "message", "retryable", "field", "retryAfterMs");
        Long retryAfter = node.hasNonNull("retryAfterMs") ? Json.reqLong(node, "retryAfterMs") : null;
        return new ChannelError(Json.text(node, "code"), Json.optText(node, "message"),
            Json.optBool(node, "retryable", false), Json.optText(node, "field"), retryAfter);
    }

    // ---- command ---------------------------------------------------------------------

    public static ObjectNode enc(CommandPacket packet) {
        ObjectNode node = Json.obj();
        node.put("version", packet.version()).put("packetId", packet.packetId()).put("kind", packet.kind())
            .put("type", packet.type().wire());
        node.put("createdAt", packet.createdAt().toString());
        node.set("correlation", enc(packet.correlation()));
        node.set("context", enc(packet.context()));
        node.set("producer", enc(packet.producer()));
        node.set("actor", enc(packet.actor()));
        node.set("payload", enc(packet.type(), packet.payload()));
        return node;
    }

    public static CommandPacket decCommand(String json) {
        JsonNode node = Json.read(json);
        Json.onlyFields(node, "version", "packetId", "kind", "type", "createdAt", "correlation", "context",
            "producer", "actor", "payload");
        CommandType type = CommandType.parse(Json.text(node, "type"));
        return new CommandPacket(Json.text(node, "version"), Json.text(node, "packetId"), type,
            Json.instant(node, "createdAt"), decCorrelation(Json.obj(node, "correlation")),
            decContext(Json.obj(node, "context")), decProducer(Json.obj(node, "producer")),
            decActor(Json.obj(node, "actor")), decCommandPayload(type, Json.obj(node, "payload")));
    }

    private static ObjectNode enc(CommandType type, CommandPayload payload) {
        ObjectNode node = Json.obj();
        switch (payload) {
            case CommandPayload.InputSubmit submit -> {
                node.set("message", ContentJson.encode(submit.message()));
                node.put("interpretation", submit.interpretation().wire());
            }
            case CommandPayload.SessionList list -> {
                if (list.agentRef() != null) {
                    node.put("agentRef", list.agentRef());
                }
                node.set("page", enc(list.page()));
                if (list.sessionWorkspaceRef()!=null) node.put("sessionWorkspaceRef",list.sessionWorkspaceRef());
            }
            case CommandPayload.SessionSelect select -> node.put("scopeRef", select.scopeRef())
                .put("expectedBindingRevision", select.expectedBindingRevision());
            case CommandPayload.SelectionGet ignored -> {}
            case CommandPayload.WorkspaceList ignored -> {}
            case CommandPayload.WorkspaceSelect select -> node.put("scopeRef",select.scopeRef())
                .put("sessionWorkspaceRef",select.sessionWorkspaceRef()).put("expectedBindingRevision",select.expectedBindingRevision());
            case CommandPayload.SessionGet ignored -> {
            }
            case CommandPayload.TaskGet ignored -> {
            }
            case CommandPayload.TaskCancel cancel -> {
                if (cancel.reason() != null) {
                    node.put("reason", cancel.reason());
                }
            }
            case CommandPayload.InteractionRespond respond -> node.setAll(enc(respond.payload()));
            case CommandPayload.SubscriptionCreate create -> {
                node.set("resource", enc(create.resource()));
                ArrayNode eventTypes = node.putArray("eventTypes");
                create.eventTypes().forEach(value -> eventTypes.add(value.wire()));
                node.put("destinationRef", create.destinationRef());
                node.put("destinationRevision", create.destinationRevision());
                if (create.resumeCursor() != null) {
                    node.put("resumeCursor", create.resumeCursor());
                }
            }
            case CommandPayload.SubscriptionRemove remove -> node.put("subscriptionRef", remove.subscriptionRef());
        }
        return node;
    }

    private static CommandPayload decCommandPayload(CommandType type, JsonNode node) {
        return switch (type) {
            case INPUT_SUBMIT -> {
                Json.onlyFields(node, "message", "interpretation");
                yield new CommandPayload.InputSubmit(ContentJson.decodeMessage(Json.obj(node, "message")),
                    interpretation(Json.optText(node, "interpretation")));
            }
            case SESSION_LIST -> {
                Json.onlyFields(node, "agentRef", "page", "sessionWorkspaceRef");
                JsonNode page = Json.optObj(node, "page");
                yield new CommandPayload.SessionList(Json.optText(node, "agentRef"),
                    page == null ? PageRequest.first() : decPage(page),Json.optText(node,"sessionWorkspaceRef"));
            }
            case SESSION_SELECT -> {
                Json.onlyFields(node, "scopeRef", "expectedBindingRevision");
                yield new CommandPayload.SessionSelect(Json.text(node, "scopeRef"),
                    Json.reqLong(node, "expectedBindingRevision"));
            }
            case SELECTION_GET -> { Json.onlyFields(node); yield new CommandPayload.SelectionGet(); }
            case WORKSPACE_LIST -> { Json.onlyFields(node); yield new CommandPayload.WorkspaceList(); }
            case WORKSPACE_SELECT -> {
                Json.onlyFields(node,"scopeRef","sessionWorkspaceRef","expectedBindingRevision");
                yield new CommandPayload.WorkspaceSelect(Json.text(node,"scopeRef"),Json.text(node,"sessionWorkspaceRef"),Json.reqLong(node,"expectedBindingRevision"));
            }
            case SESSION_GET -> {
                Json.onlyFields(node);
                yield new CommandPayload.SessionGet();
            }
            case TASK_GET -> {
                Json.onlyFields(node);
                yield new CommandPayload.TaskGet();
            }
            case TASK_CANCEL -> {
                Json.onlyFields(node, "reason");
                yield new CommandPayload.TaskCancel(Json.optText(node, "reason"));
            }
            case INTERACTION_RESPOND -> {
                yield new CommandPayload.InteractionRespond(decRespondPayload(node));
            }
            case SUBSCRIPTION_CREATE -> {
                Json.onlyFields(node, "resource", "eventTypes", "destinationRef", "destinationRevision",
                    "resumeCursor");
                Set<EventType> eventTypes = EnumSet.noneOf(EventType.class);
                for (JsonNode item : Json.array(node, "eventTypes")) {
                    eventTypes.add(EventType.parse(item.asText()));
                }
                yield new CommandPayload.SubscriptionCreate(decResource(Json.obj(node, "resource")), eventTypes,
                    Json.text(node, "destinationRef"), Json.reqLong(node, "destinationRevision"),
                    Json.optText(node, "resumeCursor"));
            }
            case SUBSCRIPTION_REMOVE -> {
                Json.onlyFields(node, "subscriptionRef");
                yield new CommandPayload.SubscriptionRemove(Json.text(node, "subscriptionRef"));
            }
        };
    }

    public static ChannelOperation.Interpretation interpretation(String wire) {
        if (wire == null) {
            return ChannelOperation.Interpretation.LITERAL;
        }
        for (ChannelOperation.Interpretation value : ChannelOperation.Interpretation.values()) {
            if (value.wire().equals(wire)) {
                return value;
            }
        }
        throw Json.invalid("unknown interpretation: " + wire);
    }

    public static ObjectNode enc(PageRequest page) {
        ObjectNode node = Json.obj();
        if (page.cursor() != null) {
            node.put("cursor", page.cursor());
        }
        if (page.limit() != null) {
            node.put("limit", page.limit());
        }
        return node;
    }

    public static PageRequest decPage(JsonNode node) {
        Json.onlyFields(node, "cursor", "limit");
        return new PageRequest(Json.optText(node, "cursor"),
            node.hasNonNull("limit") ? (int) Json.reqLong(node, "limit") : null);
    }

    public static ObjectNode enc(SubscriptionResource resource) {
        ObjectNode node = Json.obj().put("type", resource.type());
        switch (resource) {
            case SubscriptionResource.Task task -> node.put("taskRef", task.taskRef());
            case SubscriptionResource.Session session -> node.put("targetRef", session.targetRef());
        }
        return node;
    }

    public static SubscriptionResource decResource(JsonNode node) {
        Json.onlyFields(node, "type", "taskRef", "targetRef");
        String type = Json.text(node, "type");
        return switch (type) {
            case "task" -> new SubscriptionResource.Task(Json.text(node, "taskRef"));
            case "session" -> new SubscriptionResource.Session(Json.text(node, "targetRef"));
            default -> throw Json.invalid("unknown subscription resource: " + type);
        };
    }

    public static ObjectNode enc(InteractionRespondPayload payload) {
        return Json.obj().put("interactionRef", payload.interactionRef())
            .put("expectedRevision", payload.expectedRevision())
            .set("response", ContentJson.encode(payload.response()));
    }

    public static InteractionRespondPayload decRespondPayload(JsonNode node) {
        Json.onlyFields(node, "interactionRef", "expectedRevision", "response");
        return new InteractionRespondPayload(Json.text(node, "interactionRef"),
            Json.reqLong(node, "expectedRevision"),
            ContentJson.decodeResponse(Json.obj(node, "response")));
    }

    // ---- event -----------------------------------------------------------------------

    public static ObjectNode enc(EventPacket packet) {
        ObjectNode node = Json.obj();
        node.put("version", packet.version()).put("packetId", packet.packetId()).put("kind", packet.kind())
            .put("type", packet.type().wire());
        node.put("createdAt", packet.createdAt().toString());
        node.set("correlation", enc(packet.correlation()));
        node.set("context", enc(packet.context()));
        node.set("producer", enc(packet.producer()));
        node.set("source", enc(packet.source()));
        node.put("occurredAt", packet.occurredAt().toString());
        if (packet.order() != null) {
            node.set("order", enc(packet.order()));
        }
        node.set("payload", enc(packet.type(), packet.payload()));
        return node;
    }

    public static EventPacket decEvent(String json) {
        JsonNode node = Json.read(json);
        Json.onlyFields(node, "version", "packetId", "kind", "type", "createdAt", "correlation", "context",
            "producer", "source", "occurredAt", "order", "payload");
        EventType type = EventType.parse(Json.text(node, "type"));
        JsonNode orderNode = Json.optObj(node, "order");
        return new EventPacket(Json.text(node, "version"), Json.text(node, "packetId"), type,
            Json.instant(node, "createdAt"), decCorrelation(Json.obj(node, "correlation")),
            decContext(Json.obj(node, "context")), decProducer(Json.obj(node, "producer")),
            decSource(Json.obj(node, "source")), Json.instant(node, "occurredAt"),
            orderNode == null ? null : decOrder(orderNode), decEventPayload(type, Json.obj(node, "payload")));
    }

    private static ObjectNode enc(EventType type, EventPayload payload) {
        ObjectNode node = Json.obj();
        switch (payload) {
            case EventPayload.MessageReady ready -> node.set("message", ContentJson.encode(ready.message()));
            case EventPayload.TaskStarted started -> node.put("revision", started.revision());
            case EventPayload.TaskProgress progress -> {
                node.put("revision", progress.revision());
                if (progress.summary() != null) {
                    node.put("summary", progress.summary());
                }
                if (progress.progress() != null) {
                    node.put("progress", progress.progress());
                }
            }
            case EventPayload.TaskWaiting waiting -> {
                node.put("revision", waiting.revision());
                if (waiting.reason() != null) {
                    node.put("reason", waiting.reason());
                }
            }
            case EventPayload.TaskCompleted completed -> {
                node.put("revision", completed.revision());
                node.set("resultMessageRefs", array(completed.resultMessageRefs()));
                node.set("fileRefs", array(completed.fileRefs()));
            }
            case EventPayload.TaskFailed failed -> {
                node.put("revision", failed.revision());
                node.set("error", enc(failed.error()));
                node.set("resultMessageRefs", array(failed.resultMessageRefs()));
            }
            case EventPayload.TaskCancelled cancelled -> {
                node.put("revision", cancelled.revision());
                if (cancelled.reason() != null) {
                    node.put("reason", cancelled.reason());
                }
            }
            case EventPayload.TaskArtifact artifact -> node.set("fileRefs", array(artifact.fileRefs()));
            case EventPayload.InteractionRequired required -> {
                ObjectNode payloadNode = Json.obj().put("interactionRef", required.payload().interactionRef())
                    .put("revision", required.payload().revision());
                if (required.payload().expiresAt() != null) {
                    payloadNode.put("expiresAt", required.payload().expiresAt().toString());
                }
                payloadNode.set("prompt", ContentJson.encode(required.payload().prompt()));
                node.setAll(payloadNode);
            }
            case EventPayload.InteractionResolved resolved -> node.put("interactionRef", resolved.interactionRef())
                .put("revision", resolved.revision()).put("outcome", resolved.outcome().wire());
            case EventPayload.InteractionExpired expired -> {
                node.put("interactionRef", expired.interactionRef()).put("revision", expired.revision());
                if (expired.reason() != null) {
                    node.put("reason", expired.reason());
                }
            }
            case EventPayload.InteractionCancelled cancelled -> {
                node.put("interactionRef", cancelled.interactionRef()).put("revision", cancelled.revision());
                if (cancelled.reason() != null) {
                    node.put("reason", cancelled.reason());
                }
            }
        }
        return node;
    }

    private static EventPayload decEventPayload(EventType type, JsonNode node) {
        return switch (type) {
            case MESSAGE_READY -> {
                Json.onlyFields(node, "message");
                yield new EventPayload.MessageReady(ContentJson.decodeMessage(Json.obj(node, "message")));
            }
            case TASK_STARTED -> {
                Json.onlyFields(node, "revision");
                yield new EventPayload.TaskStarted(Json.reqLong(node, "revision"));
            }
            case TASK_PROGRESS -> {
                Json.onlyFields(node, "revision", "summary", "progress");
                yield new EventPayload.TaskProgress(Json.reqLong(node, "revision"), Json.optText(node, "summary"),
                    Json.optDouble(node, "progress"));
            }
            case TASK_WAITING -> {
                Json.onlyFields(node, "revision", "reason");
                yield new EventPayload.TaskWaiting(Json.reqLong(node, "revision"), Json.optText(node, "reason"));
            }
            case TASK_COMPLETED -> {
                Json.onlyFields(node, "revision", "resultMessageRefs", "fileRefs");
                yield new EventPayload.TaskCompleted(Json.reqLong(node, "revision"),
                    strings(node, "resultMessageRefs"), strings(node, "fileRefs"));
            }
            case TASK_FAILED -> {
                Json.onlyFields(node, "revision", "error", "resultMessageRefs");
                yield new EventPayload.TaskFailed(Json.reqLong(node, "revision"),
                    decError(Json.obj(node, "error")), strings(node, "resultMessageRefs"));
            }
            case TASK_CANCELLED -> {
                Json.onlyFields(node, "revision", "reason");
                yield new EventPayload.TaskCancelled(Json.reqLong(node, "revision"), Json.optText(node, "reason"));
            }
            case TASK_ARTIFACT -> {
                Json.onlyFields(node, "fileRefs");
                yield new EventPayload.TaskArtifact(strings(node, "fileRefs"));
            }
            case INTERACTION_REQUIRED -> {
                Json.onlyFields(node, "interactionRef", "revision", "expiresAt", "prompt");
                yield new EventPayload.InteractionRequired(new InteractionRequiredPayload(
                    Json.text(node, "interactionRef"), Json.reqLong(node, "revision"),
                    Json.optInstant(node, "expiresAt"), ContentJson.decodePrompt(Json.obj(node, "prompt"))));
            }
            case INTERACTION_RESOLVED -> {
                Json.onlyFields(node, "interactionRef", "revision", "outcome");
                yield new EventPayload.InteractionResolved(Json.text(node, "interactionRef"),
                    Json.reqLong(node, "revision"), EventPayload.Outcome.parse(Json.text(node, "outcome")));
            }
            case INTERACTION_EXPIRED -> {
                Json.onlyFields(node, "interactionRef", "revision", "reason");
                yield new EventPayload.InteractionExpired(Json.text(node, "interactionRef"),
                    Json.reqLong(node, "revision"), Json.optText(node, "reason"));
            }
            case INTERACTION_CANCELLED -> {
                Json.onlyFields(node, "interactionRef", "revision", "reason");
                yield new EventPayload.InteractionCancelled(Json.text(node, "interactionRef"),
                    Json.reqLong(node, "revision"), Json.optText(node, "reason"));
            }
        };
    }

    private static ArrayNode array(List<String> values) {
        ArrayNode array = Json.arr();
        values.forEach(array::add);
        return array;
    }

    private static List<String> strings(JsonNode node, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : Json.optArray(node, field)) {
            values.add(item.asText());
        }
        return values;
    }

    // ---- receipt ---------------------------------------------------------------------

    public static ObjectNode enc(ReceiptPacket packet) {
        ObjectNode node = Json.obj();
        node.put("version", packet.version()).put("packetId", packet.packetId()).put("kind", packet.kind());
        node.put("createdAt", packet.createdAt().toString());
        node.set("correlation", enc(packet.correlation()));
        node.set("context", enc(packet.context()));
        node.set("producer", enc(packet.producer()));
        ObjectNode payload = Json.obj();
        switch (packet.payload()) {
            case ReceiptPayload.Accepted accepted -> {
                payload.put("status", "accepted");
                payload.put("acceptedBy", accepted.acceptedBy().name().toLowerCase());
            }
            case ReceiptPayload.Completed completed -> {
                payload.put("status", "completed");
                payload.set("result", enc(completed.result()));
            }
            case ReceiptPayload.Rejected rejected -> {
                payload.put("status", "rejected");
                payload.set("error", enc(rejected.error()));
            }
            case ReceiptPayload.Unknown unknown -> {
                payload.put("status", "unknown");
                payload.set("error", enc(unknown.error()));
            }
        }
        node.set("payload", payload);
        return node;
    }

    public static ObjectNode enc(OperationResult result) {
        ObjectNode node = Json.obj().put("type", result.type().wire());
        switch (result) {
            case OperationResult.InputSubmit submit -> {
                if (submit.taskRef() != null) {
                    node.put("taskRef", submit.taskRef());
                }
            }
            case OperationResult.SessionList list -> {
                ArrayNode items = node.putArray("items");
                list.items().forEach(item -> items.add(enc(item)));
                if (list.nextCursor() != null) {
                    node.put("nextCursor", list.nextCursor());
                }
            }
            case OperationResult.WorkspaceList list -> {
                var items = node.putArray("items"); list.items().forEach(item -> items.add(enc(item)));
                node.set("selection",enc(list.selection()));
            }
            case OperationResult.SelectionGet get -> node.set("selection",enc(get.selection()));
            case OperationResult.WorkspaceSelect select -> node.set("selection",enc(select.selection()));
            case OperationResult.SessionGet get -> {
                node.set("session",enc(get.session()));
                if(get.interactions()!=null){var list=node.putArray("interactions");get.interactions().forEach(e->list.add(enc(e)));}
            }
            case OperationResult.SessionSelect select -> node.put("scopeRef", select.scopeRef())
                .put("targetRef", select.targetRef()).put("revision", select.revision()).put("sessionWorkspaceRef", select.sessionWorkspaceRef());
            case OperationResult.TaskGet get -> node.set("task", enc(get.task()));
            case OperationResult.TaskCancel cancel -> node.put("taskRef", cancel.taskRef())
                .put("status", cancel.status().wire());
            case OperationResult.InteractionRespond respond -> node.put("interactionRef", respond.interactionRef())
                .put("status", "applied");
            case OperationResult.SubscriptionCreate create -> node.put("subscriptionRef", create.subscriptionRef());
            case OperationResult.SubscriptionRemove remove -> node.put("subscriptionRef", remove.subscriptionRef());
        }
        return node;
    }

    public static ObjectNode enc(com.netlivecowork.channel.contract.SessionWorkspace workspace) {
        return Json.obj().put("sessionWorkspaceRef",workspace.sessionWorkspaceRef()).put("label",workspace.label());
    }
    public static ObjectNode enc(com.netlivecowork.channel.contract.NavigationSelection selection) {
        return Json.obj().put("scopeRef",selection.scopeRef()).put("sessionWorkspaceRef",selection.sessionWorkspaceRef())
            .put("targetRef",selection.targetRef()).put("revision",selection.revision());
    }
    private static com.netlivecowork.channel.contract.NavigationSelection decSelection(JsonNode node) {
        Json.onlyFields(node,"scopeRef","sessionWorkspaceRef","targetRef","revision");
        return new com.netlivecowork.channel.contract.NavigationSelection(Json.text(node,"scopeRef"),Json.optText(node,"sessionWorkspaceRef"),
            Json.optText(node,"targetRef"),Json.reqLong(node,"revision"));
    }
    private static com.netlivecowork.channel.contract.SessionWorkspace decWorkspace(JsonNode node) {
        Json.onlyFields(node,"sessionWorkspaceRef","label");
        return new com.netlivecowork.channel.contract.SessionWorkspace(Json.text(node,"sessionWorkspaceRef"),Json.text(node,"label"));
    }
    public static ObjectNode enc(SessionSummary summary) {
        ObjectNode node = Json.obj().put("targetRef", summary.targetRef()).put("label", summary.label())
            .put("available", summary.available());
        if (summary.agentRef() != null) {
            node.put("agentRef", summary.agentRef());
        }
        if (summary.workspace() != null) node.set("workspace",enc(summary.workspace()));
        if (summary.execution() != null) {
            var execution = summary.execution();
            var facts = Json.obj().put("status", execution.status()).put("revision", execution.revision())
                .put("observedAt", execution.observedAt().toString());
            if (execution.rawStatus() != null) facts.put("rawStatus", execution.rawStatus());
            if (execution.input() != null) facts.set("input", Json.obj()
                .put("allowed", execution.input().allowed()).put("mode", execution.input().mode())
                .put("reason", execution.input().reason()));
            node.set("execution", facts);
        }
        return node;
    }

    public static ObjectNode enc(TaskSnapshot snapshot) {
        ObjectNode node = Json.obj().put("taskRef", snapshot.taskRef()).put("targetRef", snapshot.targetRef())
            .put("status", snapshot.status().wire());
        if (!snapshot.summary().isEmpty()) {
            ArrayNode content = node.putArray("summary");
            snapshot.summary().forEach(part -> content.add(ContentJson.encode(part)));
        }
        return node;
    }

    public static ObjectNode enc(ChannelResponse response) {
        ObjectNode node = Json.obj();
        switch (response) {
            case ChannelResponse.Completed completed -> {
                node.put("requestId", completed.requestId()).put("status", "completed");
                node.set("result", enc(completed.result()));
            }
            case ChannelResponse.Accepted accepted -> {
                node.put("requestId", accepted.requestId()).put("status", "accepted")
                    .put("operationRef", accepted.operationRef()).put("acceptedBy", accepted.acceptedBy().name().toLowerCase());
                if (accepted.taskRef() != null) {
                    node.put("taskRef", accepted.taskRef());
                }
            }
            case ChannelResponse.Rejected rejected -> {
                node.put("requestId", rejected.requestId()).put("status", "rejected");
                node.set("error", enc(rejected.error()));
            }
            case ChannelResponse.Unknown unknown -> {
                node.put("requestId", unknown.requestId()).put("status", "unknown");
                if (unknown.operationRef() != null) {
                    node.put("operationRef", unknown.operationRef());
                }
                node.set("error", enc(unknown.error()));
            }
        }
        return node;
    }

    public static ObjectNode enc(DeliveryReceipt receipt) {
        ObjectNode node = Json.obj();
        switch (receipt) {
            case DeliveryReceipt.Accepted accepted -> {
                node.put("deliveryId", accepted.deliveryId()).put("status", "accepted");
                if (accepted.receiptRef() != null) {
                    node.put("receiptRef", accepted.receiptRef());
                }
            }
            case DeliveryReceipt.Failed failed -> {
                node.put("deliveryId", failed.deliveryId()).put("status", "failed");
                node.set("error", enc(failed.error()));
            }
            case DeliveryReceipt.Unknown unknown -> {
                node.put("deliveryId", unknown.deliveryId()).put("status", "unknown");
                node.set("error", enc(unknown.error()));
            }
        }
        return node;
    }

    public static ObjectNode enc(DeliveryAttempt attempt) {
        return Json.obj().put("timeoutMs", attempt.timeoutMs());
    }

    // ---- delivery --------------------------------------------------------------------

    public static ObjectNode enc(ChannelDelivery delivery) {
        ObjectNode node = Json.obj().put("version", delivery.version())
            .put("deliveryId", delivery.deliveryId());
        node.set("packet", enc(delivery.packet()));
        node.set("routing", Json.obj().put("entryRef", delivery.routing().entryRef())
            .put("destinationRef", delivery.routing().destinationRef())
            .put("destinationRevision", delivery.routing().destinationRevision()));
        ObjectNode presentation=Json.obj().put("sourceLabel", delivery.presentation().sourceLabel());
        if(delivery.presentation().source()!=null) presentation.set("source",Json.mapper().valueToTree(delivery.presentation().source()));
        if(delivery.presentation().notification()!=null) presentation.set("notification",Json.mapper().valueToTree(delivery.presentation().notification()));
        node.set("presentation", presentation);
        ArrayNode actions = Json.arr();
        delivery.actions().forEach(action -> actions.add(
            Json.obj().put("actionRef", action.actionRef()).put("label", action.label())));
        node.set("actions", actions);
        return node;
    }

    public static ChannelDelivery decDelivery(String json) {
        JsonNode node = Json.read(json);
        Json.onlyFields(node, "version", "deliveryId", "packet", "routing", "presentation", "actions");
        JsonNode routing = Json.obj(node, "routing");
        Json.onlyFields(routing, "entryRef", "destinationRef", "destinationRevision");
        JsonNode presentation = Json.obj(node, "presentation");
        Json.onlyFields(presentation, "sourceLabel", "source", "notification");
        ChannelDelivery.Source source=null;
        ChannelDelivery.Notification notification=null;
        if(presentation.has("source")) {
            JsonNode s=Json.obj(presentation,"source");
            Json.onlyFields(s,"sessionWorkspaceRef","workspaceLabel","sessionLabel","taskSummary");
            source=new ChannelDelivery.Source(Json.optText(s,"sessionWorkspaceRef"),Json.optText(s,"workspaceLabel"),Json.optText(s,"sessionLabel"),Json.optText(s,"taskSummary"));
        }
        if(presentation.has("notification")) {
            JsonNode n=Json.obj(presentation,"notification");Json.onlyFields(n,"kind","origin","navigation");
            JsonNode nav=Json.obj(n,"navigation");Json.onlyFields(nav,"kind");
            notification=new ChannelDelivery.Notification(Json.text(n,"kind"),Json.text(n,"origin"),new ChannelDelivery.Navigation(Json.text(nav,"kind")));
        }
        List<ChannelDelivery.Action> actions = new ArrayList<>();
        for (JsonNode action : Json.optArray(node, "actions")) {
            Json.onlyFields(action, "actionRef", "label");
            actions.add(new ChannelDelivery.Action(Json.text(action, "actionRef"), Json.text(action, "label")));
        }
        return new ChannelDelivery(Json.text(node, "version"), Json.text(node, "deliveryId"),
            decEvent(Json.write(Json.obj(node, "packet"))),
            new ChannelDelivery.Routing(Json.text(routing, "entryRef"), Json.text(routing, "destinationRef"),
                Json.reqLong(routing, "destinationRevision")),
            new ChannelDelivery.Presentation(Json.text(presentation, "sourceLabel"),source,notification), actions);
    }

    // ---- backend events ---------------------------------------------------------------

    public static ObjectNode enc(VerifiedBackendEvent event) {
        ObjectNode node = Json.obj().put("backendEventRef", event.backendEventRef());
        node.set("source", enc(event.source()));
        if (event.operationRef() != null) {
            node.put("operationRef", event.operationRef());
        }
        if (event.targetRef() != null) {
            node.put("targetRef", event.targetRef());
        }
        if (event.taskRef() != null) {
            node.put("taskRef", event.taskRef());
        }
        node.put("type", event.type().wire());
        node.put("occurredAt", event.occurredAt().toString());
        if (event.order() != null) {
            node.set("order", enc(event.order()));
        }
        node.set("payload", enc(event.type(), event.payload()));
        return node;
    }

    public static VerifiedBackendEvent decVerifiedEvent(String json) {
        JsonNode node = Json.read(json);
        Json.onlyFields(node, "backendEventRef", "source", "operationRef", "targetRef", "taskRef", "type",
            "occurredAt", "order", "payload");
        EventType type = EventType.parse(Json.text(node, "type"));
        JsonNode orderNode = Json.optObj(node, "order");
        return new VerifiedBackendEvent(Json.text(node, "backendEventRef"), decSource(Json.obj(node, "source")),
            Json.optText(node, "operationRef"), Json.optText(node, "targetRef"), Json.optText(node, "taskRef"),
            type, Json.instant(node, "occurredAt"), orderNode == null ? null : decOrder(orderNode),
            decAgentPayload(type, Json.obj(node, "payload")));
    }

    public static ObjectNode enc(AgentEvent event) {
        ObjectNode node = Json.obj().put("version", event.version()).put("eventId", event.eventId())
            .put("type", event.type()).put("occurredAt", event.occurredAt().toString());
        ObjectNode resource = node.putObject("resource").put("sessionId", event.resource().sessionId());
        if (event.resource().runId() != null) {
            resource.put("runId", event.resource().runId());
        }
        if (event.resource().taskId() != null) {
            resource.put("taskId", event.resource().taskId());
        }
        if (event.correlation() != null) {
            ObjectNode correlation = node.putObject("correlation");
            if (event.correlation().requestKey() != null) {
                correlation.put("requestKey", event.correlation().requestKey());
            }
            if (event.correlation().causedByEventId() != null) {
                correlation.put("causedByEventId", event.correlation().causedByEventId());
            }
        }
        if (event.order() != null) {
            node.set("order", enc(event.order()));
        }
        node.set("payload", enc(event.eventType(), event.payload()));
        return node;
    }

    public static AgentEvent decAgentEvent(String json) {
        JsonNode node = Json.read(json);
        Json.onlyFields(node, "version", "eventId", "type", "occurredAt", "resource", "correlation", "order",
            "payload");
        JsonNode resourceNode = Json.obj(node, "resource");
        Json.onlyFields(resourceNode, "sessionId", "runId", "taskId");
        AgentEvent.AgentCorrelation correlation = null;
        JsonNode correlationNode = Json.optObj(node, "correlation");
        if (correlationNode != null) {
            Json.onlyFields(correlationNode, "requestKey", "causedByEventId");
            correlation = new AgentEvent.AgentCorrelation(Json.optText(correlationNode, "requestKey"),
                Json.optText(correlationNode, "causedByEventId"));
        }
        JsonNode orderNode = Json.optObj(node, "order");
        EventType type = EventType.parse(Json.text(node, "type"));
        var payload=decAgentPayload(type, Json.obj(node,"payload"));
        if(payload instanceof AgentEventPayload.InteractionRequired required && resourceNode.hasNonNull("taskId")) {
            var value=required.interaction();
            payload=new AgentEventPayload.InteractionRequired(new BackendInteraction(value.backendInteractionId(),value.revision(),value.expiresAt(),value.prompt(),Json.text(resourceNode,"taskId")));
        }
        return new AgentEvent(Json.text(node, "version"), Json.text(node, "eventId"), Json.text(node, "type"),
            Json.instant(node, "occurredAt"),
            new AgentEvent.Resource(Json.text(resourceNode, "sessionId"), Json.optText(resourceNode, "runId"),
                Json.optText(resourceNode, "taskId")),
            correlation, orderNode == null ? null : decOrder(orderNode),
            payload);
    }

    private static ObjectNode enc(EventType type, AgentEventPayload payload) {
        ObjectNode node = Json.obj();
        switch (payload) {
            case AgentEventPayload.MessageReady ready -> {
                ArrayNode content = node.putArray("content");
                ready.content().forEach(part -> content.add(ContentJson.encode(part)));
                node.put("backendMessageId", ready.backendMessageId());
                if (ready.replyToBackendMessageId() != null) {
                    node.put("replyToBackendMessageId", ready.replyToBackendMessageId());
                }
            }
            case AgentEventPayload.TaskStarted started -> node.put("backendTaskId", started.backendTaskId())
                .put("revision", started.revision());
            case AgentEventPayload.TaskProgress progress -> {
                node.put("backendTaskId", progress.backendTaskId()).put("revision", progress.revision());
                if (progress.summary() != null) {
                    node.put("summary", progress.summary());
                }
                if (progress.progress() != null) {
                    node.put("progress", progress.progress());
                }
            }
            case AgentEventPayload.TaskWaiting waiting -> {
                node.put("backendTaskId", waiting.backendTaskId()).put("revision", waiting.revision());
                if (waiting.reason() != null) {
                    node.put("reason", waiting.reason());
                }
            }
            case AgentEventPayload.TaskCompleted completed -> {
                node.put("backendTaskId", completed.backendTaskId()).put("revision", completed.revision());
                ArrayNode ids = node.putArray("backendResultMessageIds");
                completed.backendResultMessageIds().forEach(ids::add);
                node.set("files", files(completed.files()));
            }
            case AgentEventPayload.TaskFailed failed -> {
                node.put("backendTaskId", failed.backendTaskId()).put("revision", failed.revision());
                node.set("error", enc(failed.error()));
                ArrayNode ids = node.putArray("backendResultMessageIds");
                failed.backendResultMessageIds().forEach(ids::add);
            }
            case AgentEventPayload.TaskCancelled cancelled -> {
                node.put("backendTaskId", cancelled.backendTaskId()).put("revision", cancelled.revision());
                if (cancelled.reason() != null) {
                    node.put("reason", cancelled.reason());
                }
            }
            case AgentEventPayload.TaskArtifact artifact -> {
                node.put("backendTaskId", artifact.backendTaskId());
                node.set("files", files(artifact.files()));
            }
            case AgentEventPayload.InteractionRequired required -> {
                ObjectNode interaction = node.putObject("interaction")
                    .put("backendInteractionId", required.interaction().backendInteractionId())
                    .put("revision", required.interaction().revision());
                if (required.interaction().expiresAt() != null) {
                    interaction.put("expiresAt", required.interaction().expiresAt().toString());
                }
                interaction.set("prompt", ContentJson.encode(required.interaction().prompt()));
                if(required.interaction().backendTaskId()!=null)interaction.put("backendTaskId",required.interaction().backendTaskId());
            }
            case AgentEventPayload.InteractionResolved resolved -> {
                node.set("ref", enc(resolved.ref()));
                node.put("outcome", resolved.outcome().wire());
            }
            case AgentEventPayload.InteractionExpired expired -> {
                node.set("ref", enc(expired.ref()));
                if (expired.reason() != null) {
                    node.put("reason", expired.reason());
                }
            }
            case AgentEventPayload.InteractionCancelled cancelled -> {
                node.set("ref", enc(cancelled.ref()));
                if (cancelled.reason() != null) {
                    node.put("reason", cancelled.reason());
                }
            }
        }
        return node;
    }

    private static ArrayNode files(List<com.netlivecowork.channel.contract.BackendFileGrant> grants) {
        ArrayNode array = Json.arr();
        grants.forEach(grant -> array.add(ContentJson.encode(grant)));
        return array;
    }

    private static List<com.netlivecowork.channel.contract.BackendFileGrant> decFiles(JsonNode node) {
        List<com.netlivecowork.channel.contract.BackendFileGrant> grants = new ArrayList<>();
        for (JsonNode item : Json.optArray(node, "files")) {
            grants.add(ContentJson.decodeBackendFile(item));
        }
        return grants;
    }

    private static AgentEventPayload decAgentPayload(EventType type, JsonNode node) {
        return switch (type) {
            case MESSAGE_READY -> {
                Json.onlyFields(node, "content", "backendMessageId", "replyToBackendMessageId");
                yield new AgentEventPayload.MessageReady(ContentJson.decodeNormalizedContent(node.get("content")),
                    Json.text(node, "backendMessageId"), Json.optText(node, "replyToBackendMessageId"));
            }
            case TASK_STARTED -> {
                Json.onlyFields(node, "backendTaskId", "revision");
                yield new AgentEventPayload.TaskStarted(Json.text(node, "backendTaskId"),
                    Json.reqLong(node, "revision"));
            }
            case TASK_PROGRESS -> {
                Json.onlyFields(node, "backendTaskId", "revision", "summary", "progress");
                yield new AgentEventPayload.TaskProgress(Json.text(node, "backendTaskId"),
                    Json.reqLong(node, "revision"), Json.optText(node, "summary"),
                    Json.optDouble(node, "progress"));
            }
            case TASK_WAITING -> {
                Json.onlyFields(node, "backendTaskId", "revision", "reason");
                yield new AgentEventPayload.TaskWaiting(Json.text(node, "backendTaskId"),
                    Json.reqLong(node, "revision"), Json.optText(node, "reason"));
            }
            case TASK_COMPLETED -> {
                Json.onlyFields(node, "backendTaskId", "revision", "backendResultMessageIds", "files");
                yield new AgentEventPayload.TaskCompleted(Json.text(node, "backendTaskId"),
                    Json.reqLong(node, "revision"), strings(node, "backendResultMessageIds"), decFiles(node));
            }
            case TASK_FAILED -> {
                Json.onlyFields(node, "backendTaskId", "revision", "error", "backendResultMessageIds");
                yield new AgentEventPayload.TaskFailed(Json.text(node, "backendTaskId"),
                    Json.reqLong(node, "revision"), decError(Json.obj(node, "error")),
                    strings(node, "backendResultMessageIds"));
            }
            case TASK_CANCELLED -> {
                Json.onlyFields(node, "backendTaskId", "revision", "reason");
                yield new AgentEventPayload.TaskCancelled(Json.text(node, "backendTaskId"),
                    Json.reqLong(node, "revision"), Json.optText(node, "reason"));
            }
            case TASK_ARTIFACT -> {
                Json.onlyFields(node, "backendTaskId", "files");
                yield new AgentEventPayload.TaskArtifact(Json.text(node, "backendTaskId"), decFiles(node));
            }
            case INTERACTION_REQUIRED -> {
                Json.onlyFields(node, "interaction");
                JsonNode interaction = Json.obj(node, "interaction");
                Json.onlyFields(interaction, "backendInteractionId", "revision", "expiresAt", "prompt", "backendTaskId");
                yield new AgentEventPayload.InteractionRequired(new BackendInteraction(
                    Json.text(interaction, "backendInteractionId"), Json.reqLong(interaction, "revision"),
                    Json.optInstant(interaction, "expiresAt"),
                    ContentJson.decodePrompt(Json.obj(interaction, "prompt")),Json.optText(interaction,"backendTaskId")));
            }
            case INTERACTION_RESOLVED -> {
                Json.onlyFields(node, "ref", "outcome");
                yield new AgentEventPayload.InteractionResolved(decInteractionRef(Json.obj(node, "ref")),
                    EventPayload.Outcome.parse(Json.text(node, "outcome")));
            }
            case INTERACTION_EXPIRED -> {
                Json.onlyFields(node, "ref", "reason");
                yield new AgentEventPayload.InteractionExpired(decInteractionRef(Json.obj(node, "ref")),
                    Json.optText(node, "reason"));
            }
            case INTERACTION_CANCELLED -> {
                Json.onlyFields(node, "ref", "reason");
                yield new AgentEventPayload.InteractionCancelled(decInteractionRef(Json.obj(node, "ref")),
                    Json.optText(node, "reason"));
            }
        };
    }

    private static ObjectNode enc(BackendInteractionRef ref) {
        return Json.obj().put("backendInteractionId", ref.backendInteractionId()).put("revision", ref.revision());
    }

    private static BackendInteractionRef decInteractionRef(JsonNode node) {
        Json.onlyFields(node, "backendInteractionId", "revision");
        return new BackendInteractionRef(Json.text(node, "backendInteractionId"), Json.reqLong(node, "revision"));
    }

    // ---- ingress ---------------------------------------------------------------------

    public static IngressRequest decIngress(String json) {
        JsonNode node = Json.read(json);
        Json.onlyFields(node, "version", "requestId", "identity", "routing", "operation");
        JsonNode identity = Json.obj(node, "identity");
        Json.onlyFields(identity, "entryRef", "callerRef");
        JsonNode routing = Json.obj(node, "routing");
        Json.onlyFields(routing, "scopeRef", "replyTargetRef");
        JsonNode operation = Json.obj(node, "operation");
        Json.onlyFields(operation, "type", "payload");
        JsonNode payload = operation.get("payload");
        if (payload == null || payload.isNull()) {
            payload = Json.obj();
        }
        if (!payload.isObject()) {
            throw Json.invalid("operation.payload must be an object");
        }
        return new IngressRequest(Json.text(node, "version"), Json.text(node, "requestId"),
            new IngressRequest.Identity(Json.text(identity, "entryRef"), Json.text(identity, "callerRef")),
            new IngressRequest.Routing(Json.optText(routing, "scopeRef"), Json.optText(routing, "replyTargetRef")),
            decOperation(Json.text(operation, "type"), payload));
    }

    public static ObjectNode enc(IngressRequest request) {
        ObjectNode node = Json.obj().put("version", request.version()).put("requestId", request.requestId());
        node.set("identity", Json.obj().put("entryRef", request.identity().entryRef())
            .put("callerRef", request.identity().callerRef()));
        ObjectNode routing = node.putObject("routing");
        if (request.routing().scopeRef() != null) {
            routing.put("scopeRef", request.routing().scopeRef());
        }
        if (request.routing().replyTargetRef() != null) {
            routing.put("replyTargetRef", request.routing().replyTargetRef());
        }
        node.set("operation", enc(request.operation()));
        return node;
    }

    public static ObjectNode enc(ChannelOperation operation) {
        ObjectNode node = Json.obj().put("type", operation.type());
        node.set("payload", encPayload(operation));
        return node;
    }

    private static ObjectNode encPayload(ChannelOperation operation) {
        ObjectNode node = Json.obj();
        switch (operation) {
            case ChannelOperation.InputSubmit submit -> {
                if (submit.targetRef() != null) {
                    node.put("targetRef", submit.targetRef());
                }
                node.put("interpretation", submit.interpretation().wire());
                if (submit.expectedBindingRevision()!=null) node.put("expectedBindingRevision",submit.expectedBindingRevision());
                ArrayNode content = node.putArray("content");
                submit.content().forEach(part -> content.add(ContentJson.encode(part)));
                if (submit.replyToMessageRef() != null) {
                    node.put("replyToMessageRef", submit.replyToMessageRef());
                }
            }
            case ChannelOperation.SessionList list -> {
                if (list.agentRef() != null) {
                    node.put("agentRef", list.agentRef());
                }
                node.set("page", enc(list.page()));
                if (list.sessionWorkspaceRef()!=null) node.put("sessionWorkspaceRef",list.sessionWorkspaceRef());
            }
            case ChannelOperation.SessionSelect select -> node.put("targetRef", select.targetRef())
                .put("expectedBindingRevision", select.expectedBindingRevision());
            case ChannelOperation.SelectionGet ignored -> {}
            case ChannelOperation.WorkspaceList ignored -> {}
            case ChannelOperation.WorkspaceSelect select -> node.put("sessionWorkspaceRef",select.sessionWorkspaceRef())
                .put("expectedBindingRevision",select.expectedBindingRevision());
            case ChannelOperation.SessionGet get -> node.put("targetRef", get.targetRef());
            case ChannelOperation.TaskGet get -> node.put("taskRef", get.taskRef());
            case ChannelOperation.TaskCancel cancel -> {
                node.put("taskRef", cancel.taskRef());
                if (cancel.reason() != null) {
                    node.put("reason", cancel.reason());
                }
            }
            case ChannelOperation.InteractionRespond respond -> node.setAll(enc(respond.payload()));
            case ChannelOperation.SubscriptionCreate create -> {
                node.set("resource", enc(create.resource()));
                ArrayNode eventTypes = node.putArray("eventTypes");
                create.eventTypes().forEach(value -> eventTypes.add(value.wire()));
                node.put("destinationRef", create.destinationRef());
                if (create.resumeCursor() != null) {
                    node.put("resumeCursor", create.resumeCursor());
                }
            }
            case ChannelOperation.SubscriptionRemove remove -> node.put("subscriptionRef", remove.subscriptionRef());
        }
        return node;
    }

    public static ChannelOperation decOperation(String type, JsonNode node) {
        return switch (type) {
            case "input.submit" -> {
                Json.onlyFields(node, "targetRef", "interpretation", "content", "replyToMessageRef", "type", "expectedBindingRevision");
                yield new ChannelOperation.InputSubmit(Json.optText(node, "targetRef"),
                    interpretation(Json.optText(node, "interpretation")),
                    ContentJson.decodeContent(node.get("content")), Json.optText(node, "replyToMessageRef"),
                    node.has("expectedBindingRevision")?Json.reqLong(node,"expectedBindingRevision"):null);
            }
            case "session.list" -> {
                Json.onlyFields(node, "agentRef", "page", "type", "sessionWorkspaceRef");
                JsonNode page = Json.optObj(node, "page");
                yield new ChannelOperation.SessionList(Json.optText(node, "agentRef"),
                    page == null ? PageRequest.first() : decPage(page),Json.optText(node,"sessionWorkspaceRef"));
            }
            case "session.select" -> {
                Json.onlyFields(node, "targetRef", "expectedBindingRevision", "type");
                yield new ChannelOperation.SessionSelect(Json.text(node, "targetRef"),
                    Json.reqLong(node, "expectedBindingRevision"));
            }
            case "selection.get" -> { Json.onlyFields(node); yield new ChannelOperation.SelectionGet(); }
            case "workspace.list" -> { Json.onlyFields(node); yield new ChannelOperation.WorkspaceList(); }
            case "workspace.select" -> {
                Json.onlyFields(node,"sessionWorkspaceRef","expectedBindingRevision");
                yield new ChannelOperation.WorkspaceSelect(Json.text(node,"sessionWorkspaceRef"),Json.reqLong(node,"expectedBindingRevision"));
            }
            case "session.get" -> {
                Json.onlyFields(node, "targetRef", "type");
                yield new ChannelOperation.SessionGet(Json.text(node, "targetRef"));
            }
            case "task.get" -> {
                Json.onlyFields(node, "taskRef", "type");
                yield new ChannelOperation.TaskGet(Json.text(node, "taskRef"));
            }
            case "task.cancel" -> {
                Json.onlyFields(node, "taskRef", "reason", "type");
                yield new ChannelOperation.TaskCancel(Json.text(node, "taskRef"), Json.optText(node, "reason"));
            }
            case "interaction.respond" -> {
                Json.onlyFields(node, "interactionRef", "expectedRevision", "response", "type");
                yield new ChannelOperation.InteractionRespond(decRespondPayload(node));
            }
            case "subscription.create" -> {
                Json.onlyFields(node, "resource", "eventTypes", "destinationRef", "resumeCursor", "type");
                Set<EventType> eventTypes = EnumSet.noneOf(EventType.class);
                for (JsonNode item : Json.array(node, "eventTypes")) {
                    eventTypes.add(EventType.parse(item.asText()));
                }
                yield new ChannelOperation.SubscriptionCreate(decResource(Json.obj(node, "resource")), eventTypes,
                    Json.text(node, "destinationRef"), Json.optText(node, "resumeCursor"));
            }
            case "subscription.remove" -> {
                Json.onlyFields(node, "subscriptionRef", "type");
                yield new ChannelOperation.SubscriptionRemove(Json.text(node, "subscriptionRef"));
            }
            default -> throw Json.invalid("unknown operation type: " + type);
        };
    }

    public static ChannelResponse decChannelResponse(String json) {
        JsonNode node = Json.read(json);
        Json.onlyFields(node, "requestId", "status", "result", "operationRef", "taskRef", "error", "acceptedBy");
        String requestId = Json.text(node, "requestId");
        String status = Json.text(node, "status");
        return switch (status) {
            case "completed" -> new ChannelResponse.Completed(requestId,
                decOperationResult(Json.obj(node, "result")));
            case "accepted" -> new ChannelResponse.Accepted(requestId, Json.text(node, "operationRef"),
                Json.optText(node, "taskRef"), switch (Json.optText(node,"acceptedBy") == null ? "channel" : Json.text(node,"acceptedBy")) {
                    case "channel" -> ReceiptPayload.AcceptedBy.CHANNEL;
                    case "agent" -> ReceiptPayload.AcceptedBy.AGENT;
                    default -> throw Json.invalid("invalid acceptance authority");
                });
            case "rejected" -> new ChannelResponse.Rejected(requestId, decError(Json.obj(node, "error")));
            case "unknown" -> new ChannelResponse.Unknown(requestId, Json.optText(node, "operationRef"),
                decError(Json.obj(node, "error")));
            default -> throw Json.invalid("unknown response status: " + status);
        };
    }

    public static OperationResult decOperationResult(JsonNode node) {
        Json.onlyFields(node, "type", "taskRef", "items", "nextCursor", "session", "scopeRef", "targetRef",
            "revision", "task", "status", "interactionRef", "subscriptionRef", "sessionWorkspaceRef", "selection", "interactions");
        CommandType type = CommandType.parse(Json.text(node, "type"));
        return switch (type) {
            case WORKSPACE_LIST -> {
                var items = new ArrayList<com.netlivecowork.channel.contract.SessionWorkspace>();
                for (var item : Json.array(node,"items")) items.add(decWorkspace(item));
                yield new OperationResult.WorkspaceList(items,decSelection(Json.obj(node,"selection")));
            }
            case SELECTION_GET -> new OperationResult.SelectionGet(decSelection(Json.obj(node,"selection")));
            case WORKSPACE_SELECT -> new OperationResult.WorkspaceSelect(decSelection(Json.obj(node,"selection")));
            case INPUT_SUBMIT -> new OperationResult.InputSubmit(Json.optText(node, "taskRef"));
            case SESSION_LIST -> new OperationResult.SessionList(decSessions(node), Json.optText(node, "nextCursor"));
            case SESSION_GET -> {
                java.util.List<EventPacket> interactions=null;
                if(node.has("interactions")){interactions=new java.util.ArrayList<>();for(var e:Json.array(node,"interactions"))interactions.add(decEvent(Json.write(e)));}
                yield new OperationResult.SessionGet(decSession(Json.obj(node,"session")),interactions);
            }
            case SESSION_SELECT -> new OperationResult.SessionSelect(Json.text(node, "scopeRef"),
                Json.text(node, "targetRef"), Json.reqLong(node, "revision"),Json.optText(node,"sessionWorkspaceRef"));
            case TASK_GET -> new OperationResult.TaskGet(decTask(Json.obj(node, "task")));
            case TASK_CANCEL -> new OperationResult.TaskCancel(Json.text(node, "taskRef"),
                TaskStatus.parse(Json.text(node, "status")));
            case INTERACTION_RESPOND -> new OperationResult.InteractionRespond(Json.text(node, "interactionRef"));
            case SUBSCRIPTION_CREATE -> new OperationResult.SubscriptionCreate(Json.text(node, "subscriptionRef"));
            case SUBSCRIPTION_REMOVE -> new OperationResult.SubscriptionRemove(Json.text(node, "subscriptionRef"));
        };
    }

    private static List<SessionSummary> decSessions(JsonNode node) {
        List<SessionSummary> items = new ArrayList<>();
        for (JsonNode item : Json.optArray(node, "items")) {
            items.add(decSession(item));
        }
        return items;
    }

    private static SessionSummary decSession(JsonNode node) {
        Json.onlyFields(node, "targetRef", "label", "available", "agentRef", "execution", "workspace");
        com.netlivecowork.channel.contract.SessionExecutionState execution = null;
        if (node.has("execution")) {
            JsonNode value = Json.obj(node, "execution");
            Json.onlyFields(value, "status", "revision", "observedAt", "rawStatus", "input");
            com.netlivecowork.channel.contract.InputAvailability input = null;
            if (value.has("input")) {
                JsonNode action = Json.obj(value, "input");
                Json.onlyFields(action, "allowed", "mode", "reason");
                if (!action.path("allowed").isBoolean()) throw new IllegalArgumentException("invalid input availability");
                input = new com.netlivecowork.channel.contract.InputAvailability(action.path("allowed").booleanValue(),
                    Json.text(action, "mode"), Json.text(action, "reason"));
            }
            execution = new com.netlivecowork.channel.contract.SessionExecutionState(Json.text(value, "status"),
                Json.reqLong(value, "revision"), Instant.parse(Json.text(value, "observedAt")), Json.optText(value, "rawStatus"), input);
        }
        return new SessionSummary(Json.text(node, "targetRef"), Json.text(node, "label"),
            Json.optText(node, "agentRef"), Json.optBool(node, "available", true), execution,
            node.has("workspace")?decWorkspace(Json.obj(node,"workspace")):null);
    }

    private static TaskSnapshot decTask(JsonNode node) {
        Json.onlyFields(node, "taskRef", "targetRef", "status", "summary");
        List<ContentPart> summary = new ArrayList<>();
        JsonNode content = node.get("summary");
        if (content != null && content.isArray()) {
            summary.addAll(ContentJson.decodeContent(content));
        }
        return new TaskSnapshot(Json.text(node, "taskRef"), Json.text(node, "targetRef"),
            TaskStatus.parse(Json.text(node, "status")), summary);
    }

    public static DeliveryReceipt decDeliveryReceipt(String json) {
        JsonNode node = Json.read(json);
        Json.onlyFields(node, "deliveryId", "status", "receiptRef", "error");
        String deliveryId = Json.text(node, "deliveryId");
        return switch (Json.text(node, "status")) {
            case "accepted" -> new DeliveryReceipt.Accepted(deliveryId, Json.optText(node, "receiptRef"));
            case "failed" -> new DeliveryReceipt.Failed(deliveryId, decError(Json.obj(node, "error")));
            case "unknown" -> new DeliveryReceipt.Unknown(deliveryId, decError(Json.obj(node, "error")));
            default -> throw Json.invalid("unknown delivery status");
        };
    }

    public static DeliveryAttempt decDeliveryAttempt(String json) {
        JsonNode node = Json.read(json);
        Json.onlyFields(node, "timeoutMs");
        return new DeliveryAttempt(Json.reqLong(node, "timeoutMs"));
    }

    public static Instant parseInstant(String value) {
        return Instant.parse(value);
    }

    public static TaskStatus taskStatus(String wire) {
        return TaskStatus.parse(wire);
    }

    public static ContentPart.PartType partType(String wire) {
        for (ContentPart.PartType type : ContentPart.PartType.values()) {
            if (type.wire().equals(wire)) {
                return type;
            }
        }
        throw Json.invalid("unknown content type: " + wire);
    }
}
