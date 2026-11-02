package com.netlivecowork.channel.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelError;
import com.netlivecowork.channel.contract.adapter.AdapterDescriptor;
import com.netlivecowork.channel.contract.adapter.AuthSession;
import com.netlivecowork.channel.contract.adapter.EntryConfig;
import com.netlivecowork.channel.contract.adapter.ProbeResult;
import com.netlivecowork.channel.contract.adapter.ValidationResult;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.l2.ManagementService;
import com.netlivecowork.channel.l2.store.DeliveryRepository;
import com.netlivecowork.channel.l2.store.DirectoryRepository;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 API：接入配置、启停、状态观测、连接测试、账号授权与投递观测。
 * 全部需要管理密钥；返回脱敏错误，不返回凭证（INV-52）。
 */
@RestController
@RequestMapping("/api/v1/channel")
final class ManagementController {

    private final ChannelRuntime runtime;

    ManagementController(ChannelRuntime runtime) {
        this.runtime = runtime;
    }

    private ManagementService management() {
        return runtime.l2().management();
    }

    @GetMapping("/adapters")
    ObjectNode adapters() {
        ArrayNode items = Json.arr();
        for (AdapterDescriptor descriptor : management().listAdapterDescriptors()) {
            ObjectNode node = items.addObject();
            node.put("type", descriptor.type());
            node.put("displayName", descriptor.displayName());
            node.put("implementationVersion", descriptor.implementationVersion());
            node.set("capabilities", Json.mapper().valueToTree(descriptor.capabilities()));
            ArrayNode modes = node.putArray("lifecycleModes");
            descriptor.lifecycleModes().forEach(mode -> modes.add(mode.name()));
        }
        return Json.obj().set("items", items);
    }

    @GetMapping("/entries")
    ObjectNode entries() {
        ArrayNode items = Json.arr();
        for (EntryConfig config : management().listEntries()) {
            items.add(entryJson(config));
        }
        return Json.obj().set("items", items);
    }

    @GetMapping("/entries/{entryRef}")
    ResponseEntity<ObjectNode> entry(@PathVariable String entryRef) {
        return respond(() -> {
            ManagementService.EntryView view = management().entryStatus(entryRef);
            ObjectNode node = entryJson(view.config());
            node.set("runtime", Json.mapper().valueToTree(Map.of(
                "generation", view.runtime().generation(),
                "state", view.runtime().state().name(),
                "retryCount", view.runtime().retryCount(),
                "nextRetryAt", view.runtime().nextRetryAt() == null ? "" : view.runtime().nextRetryAt().toString())));
            if (view.effectiveAccountKey() != null) {
                node.put("expectedAccountKey", view.effectiveAccountKey());
            }
            if (view.descriptor() != null) {
                node.put("adapterVersion", view.descriptor().implementationVersion());
            }
            return node;
        });
    }

    @PostMapping("/entries")
    ResponseEntity<ObjectNode> createEntry(@RequestBody JsonNode body) {
        return respond(() -> {
            EntryConfig draft = entryFromJson(body, true);
            EntryConfig created = management().createEntry(draft, Json.optText(body, "expectedAccountKey"));
            return entryJson(created);
        });
    }

    @PostMapping("/entries/{entryRef}/enabled")
    ResponseEntity<ObjectNode> setEnabled(@PathVariable String entryRef,
                                          @RequestParam(name = "expectedRevision") long expectedRevision,
                                          @RequestBody JsonNode body) {
        return respond(() -> entryJson(management().setEntryEnabled(entryRef,
            Json.optBool(body, "enabled", true), expectedRevision)));
    }

    @PostMapping("/entries/{entryRef}/reconnect")
    ResponseEntity<ObjectNode> reconnect(@PathVariable String entryRef) {
        return respond(() -> {
            management().reconnectEntry(entryRef);
            return Json.obj().put("entryRef", entryRef).put("status", "reconnect-requested");
        });
    }

    @PostMapping("/entries/{entryRef}/test")
    ResponseEntity<ObjectNode> test(@PathVariable String entryRef) {
        return respond(() -> {
            ProbeResult probe = management().testEntryConnection(entryRef);
            ObjectNode node = Json.obj().put("status", probe.status().name());
            if (probe.error() != null) {
                node.set("error", errorJson(probe.error()));
            }
            return node;
        });
    }

    @PostMapping("/entries/{entryRef}/validate")
    ResponseEntity<ObjectNode> validate(@RequestBody JsonNode body) {
        return respond(() -> {
            ValidationResult result = management().validateEntryConfig(entryFromJson(body, true));
            ObjectNode node = Json.obj().put("valid", result.valid());
            ArrayNode issues = node.putArray("issues");
            result.issues().forEach(issue -> issues.addObject().put("path", issue.path())
                .put("code", issue.code()).put("message", issue.message()));
            return node;
        });
    }

    @PostMapping("/entries/{entryRef}/auth")
    ResponseEntity<ObjectNode> beginAuth(@PathVariable String entryRef) {
        return respond(() -> authJson(management().beginEntryAuth(entryRef)));
    }

    @GetMapping("/entries/{entryRef}/auth/{authRef}")
    ResponseEntity<ObjectNode> authStatus(@PathVariable String entryRef, @PathVariable String authRef) {
        return respond(() -> authJson(management().entryAuthStatus(entryRef, authRef)));
    }

    @PostMapping("/entries/{entryRef}/auth/{authRef}/cancel")
    ResponseEntity<ObjectNode> cancelAuth(@PathVariable String entryRef, @PathVariable String authRef) {
        return respond(() -> {
            management().cancelEntryAuth(entryRef, authRef);
            return Json.obj().put("authRef", authRef).put("status", "cancelled");
        });
    }

    @PostMapping("/entries/{entryRef}/bindings")
    ResponseEntity<ObjectNode> bindIdentity(@PathVariable String entryRef, @RequestBody JsonNode body) {
        return respond(() -> {
            management().bindIdentity(entryRef, Json.text(body, "subjectKind"), Json.text(body, "subjectKey"),
                Json.text(body, "callerRef"));
            return Json.obj().put("entryRef", entryRef).put("callerRef", Json.text(body, "callerRef"));
        });
    }

    @PostMapping("/entries/{entryRef}/destinations")
    ResponseEntity<ObjectNode> registerDestination(@PathVariable String entryRef, @RequestBody JsonNode body) {
        return respond(() -> {
            long revision = management().registerDestination(Json.text(body, "destinationRef"), entryRef,
                Json.text(body, "audience"), body.get("protocolData"),
                Json.optLong(body, "expectedRevision", 0));
            return Json.obj().put("destinationRef", Json.text(body, "destinationRef"))
                .put("destinationRevision", revision);
        });
    }

    @GetMapping("/entries/{entryRef}/protocol-state/{key}")
    ResponseEntity<ObjectNode> protocolState(@PathVariable String entryRef, @PathVariable String key) {
        return respond(() -> {
            ObjectNode node = Json.obj().put("entryRef", entryRef).put("key", key);
            node.set("value", management().protocolState(entryRef, key));
            return node;
        });
    }

    @GetMapping("/entries/{entryRef}/inbound-dispositions")
    ResponseEntity<ObjectNode> inboundDispositions(@PathVariable String entryRef,
                                                   @RequestParam(name = "limit", defaultValue = "50")
                                                   int limit) {
        return respond(() -> {
            ArrayNode items = Json.arr();
            for (com.netlivecowork.channel.contract.InboundDisposition disposition
                : runtime.l2().inboundDispositions(entryRef, limit)) {
                ObjectNode node = items.addObject();
                node.put("sourceEventId", disposition.sourceEventId());
                node.put("code", disposition.code());
                node.put("recordedAt", disposition.recordedAt().toString());
                node.put("retainUntil", disposition.retainUntil().toString());
                node.put("expired", disposition.expired());
                node.put("gap", disposition.gap());
            }
            return Json.obj().set("items", items);
        });
    }

    @PostMapping("/entries/{entryRef}/account-rebind")
    ResponseEntity<ObjectNode> rebindAccount(@PathVariable String entryRef,
                                             @RequestParam(name = "expectedRevision") long expectedRevision) {
        return respond(() -> entryJson(management().rebindAccount(entryRef, expectedRevision)));
    }

    /** 部署级管理入口身份：由认证过滤器确立，不接受请求体自报（v2.11 §5.1.1）。 */
    private com.netlivecowork.channel.contract.grant.GrantManagement.TrustedManagementContext grantContext() {
        // The deployment token authenticates infrastructure administration, not a resource owner.
        throw new com.netlivecowork.channel.contract.ChannelContractException(
            com.netlivecowork.channel.contract.ChannelErrorCode.UNSUPPORTED_CAPABILITY,
            "grant management requires an authenticated resource principal; deployment token is insufficient");
    }

    @PostMapping("/grants")
    ResponseEntity<ObjectNode> createGrant(@RequestBody JsonNode body) {
        return respond(() -> grantReply(runtime.l2().grantManagement().createGrant(grantContext(),
            new com.netlivecowork.channel.contract.grant.GrantManagement.CreateGrantRequest(
                Json.text(body, "requestId"), grantSpec(body.path("spec")), Json.text(body, "reason")))
            .toCompletableFuture().join()));
    }

    @PutMapping("/grants/{grantRef}")
    ResponseEntity<ObjectNode> replaceGrant(@PathVariable String grantRef, @RequestBody JsonNode body) {
        return respond(() -> grantReply(runtime.l2().grantManagement().replaceGrant(grantContext(),
            new com.netlivecowork.channel.contract.grant.GrantManagement.ReplaceGrantRequest(
                Json.text(body, "requestId"), grantRef, body.path("expectedRevision").asLong(),
                grantTerms(body.path("replacement")), Json.text(body, "reason")))
            .toCompletableFuture().join()));
    }

    @PostMapping("/grants/{grantRef}/revoke")
    ResponseEntity<ObjectNode> revokeGrant(@PathVariable String grantRef, @RequestBody JsonNode body) {
        return respond(() -> grantReply(runtime.l2().grantManagement().revokeGrant(grantContext(),
            new com.netlivecowork.channel.contract.grant.GrantManagement.RevokeGrantRequest(
                Json.text(body, "requestId"), grantRef, body.path("expectedRevision").asLong(),
                Json.text(body, "reason")))
            .toCompletableFuture().join()));
    }

    @GetMapping("/grants/{grantRef}")
    ResponseEntity<ObjectNode> grant(@PathVariable String grantRef) {
        return respond(() -> grantJson(runtime.l2().grantManagement().getGrant(grantContext(), grantRef)
            .toCompletableFuture().join()));
    }

    private com.netlivecowork.channel.contract.grant.GrantManagement.GrantTerms grantTerms(JsonNode node) {
        java.util.List<com.netlivecowork.channel.contract.grant.GrantManagement.GrantPermission> permissions =
            new java.util.ArrayList<>();
        for (JsonNode item : node.path("permissions")) {
            String action = item.path("action").asText();
            permissions.add(switch (action) {
                case "target.read" -> new com.netlivecowork.channel.contract.grant.GrantManagement.TargetRead();
                case "session.select" -> new com.netlivecowork.channel.contract.grant.GrantManagement.SessionSelect();
                case "input.submit" -> new com.netlivecowork.channel.contract.grant.GrantManagement.InputSubmit();
                case "task.read" -> new com.netlivecowork.channel.contract.grant.GrantManagement.TaskRead();
                case "task.cancel" -> new com.netlivecowork.channel.contract.grant.GrantManagement.TaskCancel();
                case "interaction.respond" ->
                    new com.netlivecowork.channel.contract.grant.GrantManagement.InteractionRespond(
                        java.util.stream.StreamSupport.stream(item.path("responses").spliterator(), false)
                            .map(JsonNode::asText).toList(),
                        item.path("allowArgumentEdit").asBoolean(false));
                case "events.observe" ->
                    new com.netlivecowork.channel.contract.grant.GrantManagement.EventsObserve(
                        java.util.stream.StreamSupport.stream(item.path("eventTypes").spliterator(), false)
                            .map(entry -> com.netlivecowork.channel.contract.EventType.parse(entry.asText()))
                            .toList());
                default -> throw new IllegalArgumentException("unknown grant action: " + action);
            });
        }
        return new com.netlivecowork.channel.contract.grant.GrantManagement.GrantTerms(permissions,
            java.time.Instant.parse(node.path("validFrom").asText()),
            java.time.Instant.parse(node.path("validUntil").asText()));
    }

    private com.netlivecowork.channel.contract.grant.GrantManagement.GrantSpec grantSpec(JsonNode node) {
        return new com.netlivecowork.channel.contract.grant.GrantManagement.GrantSpec(
            grantTerms(node.path("terms")), Json.text(node, "granteePrincipalRef"),
            new com.netlivecowork.channel.contract.grant.GrantManagement.GrantResource(
                Json.text(node.path("resource"), "targetRef"),
                node.path("resource").path("resourceEpoch").asLong(),
                node.path("resource").path("taskRef").isMissingNode() ? null
                    : node.path("resource").path("taskRef").asText()));
    }

    private ObjectNode grantReply(
        com.netlivecowork.channel.contract.grant.GrantManagement.GrantMutationReply reply) {
        ObjectNode node = Json.obj();
        if (reply instanceof com.netlivecowork.channel.contract.grant.GrantManagement.GrantMutationReply.Committed committed) {
            node.put("kind", "committed");
            ObjectNode result = node.putObject("result");
            result.put("changeRef", committed.result().changeRef());
            result.put("outcome", committed.result().outcome());
            result.put("committedAt", committed.result().committedAt().toString());
            result.put("followUpRequired", committed.result().followUpRequired());
            result.set("grant", grantJson(committed.result().grant()));
        } else if (reply instanceof com.netlivecowork.channel.contract.grant.GrantManagement.GrantMutationReply.Rejected rejected) {
            node.put("kind", "rejected");
            node.set("error", Json.mapper().valueToTree(rejected.error()));
            if (rejected.currentRevision() != null) {
                node.put("currentRevision", rejected.currentRevision());
            }
        } else if (reply instanceof com.netlivecowork.channel.contract.grant.GrantManagement.GrantMutationReply.Unknown unknown) {
            node.put("kind", "unknown");
            node.put("requestId", unknown.requestId());
            node.set("error", Json.mapper().valueToTree(unknown.error()));
        }
        return node;
    }
    private ObjectNode grantJson(com.netlivecowork.channel.contract.grant.GrantManagement.GrantView view) {
        ObjectNode node = Json.obj();
        node.put("grantRef", view.grantRef());
        node.put("revision", view.revision());
        node.put("recordState", view.recordState());
        node.put("granteePrincipalRef", view.spec().granteePrincipalRef());
        node.put("targetRef", view.spec().resource().targetRef());
        node.put("resourceEpoch", view.spec().resource().resourceEpoch());
        node.put("createdBy", view.createdBy());
        node.put("updatedBy", view.updatedBy());
        node.put("createdAt", view.createdAt().toString());
        node.put("updatedAt", view.updatedAt().toString());
        node.put("validFrom", view.spec().terms().validFrom().toString());
        node.put("validUntil", view.spec().terms().validUntil().toString());
        if (view.revokedAt() != null) {
            node.put("revokedAt", view.revokedAt().toString());
        }
        return node;
    }

    @PostMapping("/entries/{entryRef}/test/inbound")
    ResponseEntity<ObjectNode> testInbound(@PathVariable String entryRef, @RequestBody JsonNode body) {
        return respond(() -> {
            Object instance = runtime.l2().connections().instance(entryRef);
            if (instance instanceof com.netlivecowork.channel.l1.fake.FakeAdapterInstance fake) {
                return com.netlivecowork.channel.contract.json.PacketJson.enc(fake.submit(
                    Json.text(body, "requestId"), Json.text(body, "callerRef"), Json.text(body, "text")));
            }
            throw new ChannelContractException(com.netlivecowork.channel.contract.ChannelErrorCode
                .UNSUPPORTED_CAPABILITY, "entry adapter does not accept test inbound");
        });
    }

    @GetMapping("/targets")
    ObjectNode targets() {
        ArrayNode items = Json.arr();
        for (DirectoryRepository.TargetRow row : management().listTargets()) {
            ObjectNode node = items.addObject();
            node.put("targetRef", row.targetRef());
            node.put("label", row.label());
            node.put("agentRef", row.agentRef() == null ? "" : row.agentRef());
            node.put("availability", row.availability().name());
            ArrayNode capabilities = node.putArray("capabilities");
            row.capabilities().forEach(capabilities::add);
        }
        return Json.obj().set("items", items);
    }

    @PostMapping("/targets/sync")
    ResponseEntity<ObjectNode> syncTargets() {
        return respond(() -> Json.obj().put("synced", management().syncTargets()));
    }

    @GetMapping("/deliveries")
    ObjectNode deliveries(@RequestParam(name = "status", required = false) String status,
                          @RequestParam(name = "limit", defaultValue = "50") int limit) {
        ArrayNode items = Json.arr();
        List<DeliveryRepository.DeliveryRow> rows = management().listDeliveries(status, limit);
        for (DeliveryRepository.DeliveryRow row : rows) {
            items.add(deliveryJson(row));
        }
        return Json.obj().set("items", items);
    }

    @GetMapping("/deliveries/{deliveryId}")
    ResponseEntity<ObjectNode> delivery(@PathVariable String deliveryId) {
        return respond(() -> deliveryJson(management().getDelivery(deliveryId)));
    }

    @PostMapping("/deliveries/{deliveryId}/reconcile")
    ResponseEntity<ObjectNode> reconcileDelivery(@PathVariable String deliveryId, @RequestBody JsonNode body) {
        return respond(() -> {
            boolean accepted = "accepted".equalsIgnoreCase(Json.text(body, "status"));
            DeliveryRepository.DeliveryRow row = management().reconcileDelivery(deliveryId, accepted,
                Json.optText(body, "receiptRef"), Json.optText(body, "note"));
            return deliveryJson(row);
        });
    }

    // ---- helpers ---------------------------------------------------------------------

    private ObjectNode entryJson(EntryConfig config) {
        ObjectNode node = Json.obj();
        node.put("entryRef", config.entryRef());
        node.put("name", config.name());
        node.put("adapterType", config.adapterType());
        node.put("configVersion", config.configVersion());
        node.put("revision", config.revision());
        node.put("accountBindingEpoch", config.accountBindingEpoch());
        node.put("enabled", config.enabled());
        node.put("channelPolicyRef", config.channelPolicyRef());
        node.set("settings", config.settings());
        node.set("connectionPolicy", Json.mapper().valueToTree(config.connectionPolicy()));
        ArrayNode credentialNames = node.putArray("credentialNames");
        config.credentials().keySet().forEach(credentialNames::add);
        return node;
    }

    private ObjectNode deliveryJson(DeliveryRepository.DeliveryRow row) {
        ObjectNode node = Json.obj();
        node.put("deliveryId", row.deliveryId());
        node.put("entryRef", row.entryRef());
        node.put("destinationRef", row.destinationRef());
        node.put("destinationRevision", row.destinationRevision());
        node.put("packetId", row.packetId());
        node.put("status", row.status().name());
        node.put("attemptCount", row.attemptCount());
        if (row.nextAttemptAt() != null) {
            node.put("nextAttemptAt", row.nextAttemptAt().toString());
        }
        if (row.lastError() != null && !row.lastError().isBlank()) {
            node.put("lastError", row.lastError());
        }
        return node;
    }

    private ObjectNode authJson(AuthSession session) {
        ObjectNode node = Json.obj().put("authRef", session.authRef())
            .put("status", session.status().name());
        if (session.expiresAt() != null) {
            node.put("expiresAt", session.expiresAt().toString());
        }
        if (session.presentation() != null) {
            node.set("presentation", Json.mapper().valueToTree(session.presentation()));
        }
        if (session.error() != null) {
            node.set("error", errorJson(session.error()));
        }
        return node;
    }

    private ObjectNode errorJson(ChannelError error) {
        ObjectNode node = Json.obj().put("code", error.code()).put("message", error.message())
            .put("retryable", error.retryable());
        if (error.field() != null) {
            node.put("field", error.field());
        }
        return node;
    }

    private EntryConfig entryFromJson(JsonNode body, boolean requireIdentity) {
        String entryRef = Json.text(body, "entryRef");
        String adapterType = Json.text(body, "adapterType");
        long accountBindingEpoch = Json.optLong(body, "accountBindingEpoch", 1);
        int configVersion = (int) Json.optLong(body, "configVersion", 1);
        Map<String, String> credentials = new java.util.LinkedHashMap<>();
        JsonNode credentialsNode = body.get("credentials");
        if (credentialsNode != null && credentialsNode.isObject()) {
            credentialsNode.fields().forEachRemaining(entry -> credentials.put(entry.getKey(),
                entry.getValue().asText()));
        }
        var policyNode = body.get("connectionPolicy");
        return new EntryConfig(runtime.properties().tenantRef(), runtime.properties().workspaceRef(), entryRef,
            Json.optText(body, "name") == null ? entryRef : Json.optText(body, "name"), adapterType,
            configVersion, Json.optLong(body, "revision", 0),
            accountBindingEpoch, Json.optBool(body, "enabled", false),
            body.hasNonNull("settings") ? body.get("settings") : Json.obj(), credentials,
            policyNode == null ? com.netlivecowork.channel.contract.adapter.ConnectionPolicy.defaults()
                : Json.mapper().convertValue(policyNode,
                    com.netlivecowork.channel.contract.adapter.ConnectionPolicy.class),
            Json.optText(body, "channelPolicyRef"));
    }

    private ResponseEntity<ObjectNode> respond(java.util.function.Supplier<ObjectNode> work) {
        try {
            return ResponseEntity.ok(work.get());
        } catch (ChannelContractException e) {
            HttpStatus status = switch (e.code()) {
                case REQUEST_NOT_FOUND -> HttpStatus.NOT_FOUND;
                case PERMISSION_DENIED, ACCOUNT_MISMATCH, AUTH_REQUIRED -> HttpStatus.FORBIDDEN;
                case REQUEST_CONFLICT, BINDING_CONFLICT, INTERACTION_STALE -> HttpStatus.CONFLICT;
                case UNSUPPORTED_CAPABILITY, UNSUPPORTED_VERSION -> HttpStatus.NOT_IMPLEMENTED;
                default -> HttpStatus.BAD_REQUEST;
            };
            return ResponseEntity.status(status).body(errorJson(e.toError()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorJson(ChannelError.of(
                    com.netlivecowork.channel.contract.ChannelErrorCode.INTERNAL_ERROR,
                    "management operation failed")));
        }
    }
}
