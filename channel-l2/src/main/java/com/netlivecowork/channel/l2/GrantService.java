package com.netlivecowork.channel.l2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netlivecowork.channel.contract.ChannelError;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.EventType;
import com.netlivecowork.channel.contract.grant.GrantManagement;
import com.netlivecowork.channel.contract.grant.GrantManagement.CreateGrantRequest;
import com.netlivecowork.channel.contract.grant.GrantManagement.GrantMutationReply;
import com.netlivecowork.channel.contract.grant.GrantManagement.GrantMutationResult;
import com.netlivecowork.channel.contract.grant.GrantManagement.GrantPermission;
import com.netlivecowork.channel.contract.grant.GrantManagement.GrantSpec;
import com.netlivecowork.channel.contract.grant.GrantManagement.GrantTerms;
import com.netlivecowork.channel.contract.grant.GrantManagement.GrantView;
import com.netlivecowork.channel.contract.grant.GrantManagement.ReplaceGrantRequest;
import com.netlivecowork.channel.contract.grant.GrantManagement.RevokeGrantRequest;
import com.netlivecowork.channel.contract.grant.GrantManagement.TrustedManagementContext;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.infra.Db;
import com.netlivecowork.channel.infra.Digests;
import com.netlivecowork.channel.infra.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * v2.11 个人 Grant 管理（L2 §5.1）：只表示正向授权；显式拒绝/共享公开是独立策略。
 * 首版管理者限定为 L2 登记的资源所有者；管理权与记录版本分离，CAS 失败返回冲突而不是覆盖。
 */
public final class GrantService implements GrantManagement.GrantManagementPort {

    private final Db db;
    private final Clock clock;
    private final PersonalSessionPolicy personalSessions;

    public GrantService(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
        this.personalSessions = new PersonalSessionPolicy(db);
    }

    @Override
    public CompletionStage<GrantMutationReply> createGrant(TrustedManagementContext context,
                                                           CreateGrantRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return db.tx(connection -> {
                requireContext(context);
                requireText(context, request == null ? null : request.requestId(), "requestId");
                if (request == null || request.spec() == null) {
                    return rejected(ChannelErrorCode.INVALID_ARGUMENT, "grant spec is required");
                }
                if (request.spec().resource() == null || request.spec().terms() == null)
                    return rejected(ChannelErrorCode.INVALID_ARGUMENT, "resource and terms are required");
                String digest = digest("create", request.requestId(), Json.write(specJson(request.spec())),
                    request.reason());
                GrantMutationReply replay = replay(context, "create", request.requestId(), digest);
                if (replay != null) {
                    return replay;
                }
                String problem = validate(context, request.spec());
                if (problem != null) return rejected(ChannelErrorCode.INVALID_ARGUMENT, problem);
                if (!mayGrant(context, request.spec())) {
                    return rejected(ChannelErrorCode.PERMISSION_DENIED,
                        "only the currently verified resource owner may create a grant");
                }
                String grantRef = Ids.next("grant");
                String changeRef = Ids.next("gchg");
                Instant now = clock.instant();
                String dedupeKey = dedupeKey(context, "create", request.requestId());
                String termsJson = Json.write(termsJson(request.spec().terms()));
                // 幂等键先在同一事务内抢占：并发同键只有一个能建规则，另一个走原结果重放。
                boolean first = db.tx(nested -> {
                    int claimed = db.update("""
                        INSERT OR IGNORE INTO l2_grant_requests(dedupe_key, request_id, operation,
                          request_digest, change_ref, outcome, grant_ref, committed_at)
                        VALUES(?,?,?,?,?,?,?,?)
                        """, dedupeKey, request.requestId(), "create", digest, changeRef, "created", grantRef,
                        now.toString());
                    if (claimed == 0) {
                        return false;
                    }
                    db.update("""
                        INSERT INTO l2_grants(grant_ref, tenant_ref, workspace_ref, grantee_principal_ref,
                          target_ref, resource_epoch, task_ref, terms_json, revision, state, created_by,
                          updated_by, created_at, updated_at, revoked_at)
                        VALUES(?,?,?,?,?,?,?,?,1,'enabled',?,?,?,?,NULL)
                        """, grantRef, context.tenantRef(), context.workspaceRef(),
                        request.spec().granteePrincipalRef(), request.spec().resource().targetRef(),
                        request.spec().resource().resourceEpoch(), request.spec().resource().taskRef(),
                        termsJson, context.principalRef(), context.principalRef(), now.toString(),
                        now.toString());
                    db.update("UPDATE l2_grants SET authority_generation=? WHERE grant_ref=?",
                        generation(request.spec().resource().targetRef()), grantRef);
                    return true;
                });
                if (!first) {
                    GrantMutationReply concurrent = replay(context, "create", request.requestId(), digest);
                    return concurrent != null ? concurrent
                        : rejected(ChannelErrorCode.REQUEST_CONFLICT, "concurrent request with different content");
                }
                remember(context, "create", request.requestId(), grantRef);
                return new GrantMutationReply.Committed(new GrantMutationResult(changeRef, "created",
                    view(grant(grantRef)), now, false));
                });
            } catch (com.netlivecowork.channel.contract.ChannelContractException e) {
                return new GrantMutationReply.Rejected(e.toError(), null);
            } catch (RuntimeException e) {
                return new GrantMutationReply.Unknown(request == null ? null : request.requestId(),
                    ChannelError.of(ChannelErrorCode.OUTCOME_UNKNOWN, "grant create outcome is unknown"));
            }
        });
    }

    @Override
    public CompletionStage<GrantMutationReply> replaceGrant(TrustedManagementContext context,
                                                            ReplaceGrantRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return db.tx(connection -> {
                requireContext(context);
                requireText(context, request == null ? null : request.requestId(), "requestId");
                if (request == null || request.grantRef() == null) {
                    return rejected(ChannelErrorCode.INVALID_ARGUMENT, "grantRef is required");
                }
                if (request.replacement() == null) return rejected(ChannelErrorCode.INVALID_ARGUMENT, "replacement required");
                String terms = Json.write(termsJson(request.replacement()));
                String digest = digest("replace", request.requestId(), request.grantRef(),
                    String.valueOf(request.expectedRevision()), terms, request.reason());
                GrantMutationReply replay = replay(context, "replace", request.requestId(), digest);
                if (replay != null) {
                    return replay;
                }
                GrantRow existing = grant(request.grantRef());
                if (existing == null) {
                    return rejected(ChannelErrorCode.REQUEST_NOT_FOUND, "unknown grant");
                }
                if (!inDomain(context, existing) || !ownsTarget(context, existing.targetRef())) {
                    return rejected(ChannelErrorCode.PERMISSION_DENIED,
                        "only the currently verified resource owner may replace a grant");
                }
                if (!"enabled".equals(existing.state()) || !clock.instant().isBefore(terms(Json.read(existing.termsJson())).validUntil())
                    || !basisCurrent(existing)) {
                    return rejected(ChannelErrorCode.GRANT_STATE_CONFLICT, "grant is already revoked");
                }
                String problem = validateTerms(context, request.replacement(), existing.resourceEpoch(),
                    existing.taskRef());
                if (problem != null) {
                    return rejected(ChannelErrorCode.INVALID_ARGUMENT, problem);
                }
                if (!mayGrant(context, new GrantSpec(request.replacement(), existing.granteePrincipalRef(),
                    new GrantManagement.GrantResource(existing.targetRef(), existing.resourceEpoch(), existing.taskRef()))))
                    return rejected(ChannelErrorCode.PERMISSION_DENIED, "replacement exceeds current delegation authority");
                if (existing.revision() != request.expectedRevision()) {
                    return new GrantMutationReply.Rejected(ChannelError.of(
                        ChannelErrorCode.GRANT_REVISION_CONFLICT, "grant revision does not match"),
                        existing.revision());
                }
                if (existing.termsJson().equals(terms)) {
                    return commit(context, "replace", request.requestId(), digest, existing.grantRef(),
                        "unchanged");
                }
                int updated = db.update("""
                    UPDATE l2_grants SET terms_json=?, revision=revision+1, updated_by=?, updated_at=?
                    WHERE grant_ref=? AND revision=? AND state='enabled'
                    """, terms, context.principalRef(), clock.instant().toString(), existing.grantRef(),
                    request.expectedRevision());
                if (updated != 1) {
                    GrantRow current = grant(existing.grantRef());
                    return new GrantMutationReply.Rejected(ChannelError.of(
                        ChannelErrorCode.GRANT_REVISION_CONFLICT, "grant revision changed"),
                        current == null ? null : current.revision());
                }
                return commit(context, "replace", request.requestId(), digest, existing.grantRef(), "replaced");
                });
            } catch (com.netlivecowork.channel.contract.ChannelContractException e) {
                return new GrantMutationReply.Rejected(e.toError(), null);
            } catch (RuntimeException e) {
                return new GrantMutationReply.Unknown(request == null ? null : request.requestId(),
                    ChannelError.of(ChannelErrorCode.OUTCOME_UNKNOWN, "grant replace outcome is unknown"));
            }
        });
    }

    @Override
    public CompletionStage<GrantMutationReply> revokeGrant(TrustedManagementContext context,
                                                           RevokeGrantRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return db.tx(connection -> {
                requireContext(context);
                requireText(context, request == null ? null : request.requestId(), "requestId");
                if (request == null || request.grantRef() == null) {
                    return rejected(ChannelErrorCode.INVALID_ARGUMENT, "grantRef is required");
                }
                String digest = digest("revoke", request.requestId(), request.grantRef(),
                    String.valueOf(request.expectedRevision()), request.reason());
                GrantMutationReply replay = replay(context, "revoke", request.requestId(), digest);
                if (replay != null) {
                    return replay;
                }
                GrantRow existing = grant(request.grantRef());
                if (existing == null) {
                    return rejected(ChannelErrorCode.REQUEST_NOT_FOUND, "unknown grant");
                }
                if (!inDomain(context, existing) || !ownsTarget(context, existing.targetRef())) {
                    return rejected(ChannelErrorCode.PERMISSION_DENIED,
                        "only the currently verified resource owner may revoke a grant");
                }
                if ("revoked".equals(existing.state())) {
                    if (existing.revision() == request.expectedRevision()) {
                        return commit(context, "revoke", request.requestId(), digest, existing.grantRef(),
                            "unchanged");
                    }
                    return new GrantMutationReply.Rejected(ChannelError.of(
                        ChannelErrorCode.GRANT_REVISION_CONFLICT, "grant revision does not match"),
                        existing.revision());
                }
                if (existing.revision() != request.expectedRevision()) {
                    return new GrantMutationReply.Rejected(ChannelError.of(
                        ChannelErrorCode.GRANT_REVISION_CONFLICT, "grant revision does not match"),
                        existing.revision());
                }
                int updated = db.update("""
                    UPDATE l2_grants SET state='revoked', revision=revision+1, updated_by=?,
                      updated_at=?, revoked_at=? WHERE grant_ref=? AND revision=? AND state='enabled'
                    """, context.principalRef(), clock.instant().toString(), clock.instant().toString(),
                    existing.grantRef(), request.expectedRevision());
                if (updated != 1) {
                    return new GrantMutationReply.Rejected(ChannelError.of(
                        ChannelErrorCode.GRANT_REVISION_CONFLICT, "grant revision changed"),
                        grant(existing.grantRef()).revision());
                }
                return commit(context, "revoke", request.requestId(), digest, existing.grantRef(), "revoked");
                });
            } catch (com.netlivecowork.channel.contract.ChannelContractException e) {
                return new GrantMutationReply.Rejected(e.toError(), null);
            } catch (RuntimeException e) {
                return new GrantMutationReply.Unknown(request == null ? null : request.requestId(),
                    ChannelError.of(ChannelErrorCode.OUTCOME_UNKNOWN, "grant revoke outcome is unknown"));
            }
        });
    }

    @Override
    public CompletionStage<GrantView> getGrant(TrustedManagementContext context, String grantRef) {
        return CompletableFuture.supplyAsync(() -> {
            requireContext(context);
            GrantRow row = grant(grantRef);
            if (row == null) {
                throw new com.netlivecowork.channel.contract.ChannelContractException(
                    ChannelErrorCode.REQUEST_NOT_FOUND, "unknown grant");
            }
            if (!inDomain(context, row) || !ownsTarget(context, row.targetRef())) {
                throw new com.netlivecowork.channel.contract.ChannelContractException(
                    ChannelErrorCode.PERMISSION_DENIED, "grant is not visible to this principal");
            }
            return view(row);
        });
    }

    /**
     * 执行期授权：所有者本人，或未撤销且在有效期内的个人 Grant 覆盖该动作。
     * 只作正向放行；显式拒绝与共享公开是独立策略，不由 Grant 推导。
     */
    public boolean canAct(String tenantRef, String workspaceRef, String principalRef, String targetRef,
                          String action) {
        try { return authorize(tenantRef, workspaceRef, principalRef, targetRef, null,
                List.of(permission(action))) != null; }
        catch (RuntimeException e) { return false; }
    }

    // ---- persistence ----------------------------------------------------------------

    private record GrantRow(String grantRef, String tenantRef, String workspaceRef, String granteePrincipalRef,
                            String targetRef, long resourceEpoch, String taskRef, String termsJson,
                            long revision, String state, String createdBy, String updatedBy, Instant createdAt,
                            Instant updatedAt, Instant revokedAt) {
    }

    private GrantRow grant(String grantRef) {
        return db.queryOne("SELECT * FROM l2_grants WHERE grant_ref=?", rs -> new GrantRow(
            Db.str(rs, "grant_ref"), Db.str(rs, "tenant_ref"), Db.str(rs, "workspace_ref"),
            Db.str(rs, "grantee_principal_ref"), Db.str(rs, "target_ref"), rs.getLong("resource_epoch"),
            Db.str(rs, "task_ref"), Db.str(rs, "terms_json"), rs.getLong("revision"), Db.str(rs, "state"),
            Db.str(rs, "created_by"), Db.str(rs, "updated_by"), Instant.parse(Db.str(rs, "created_at")),
            Instant.parse(Db.str(rs, "updated_at")),
            Db.str(rs, "revoked_at") == null ? null : Instant.parse(Db.str(rs, "revoked_at"))), grantRef);
    }

    private boolean ownsTarget(TrustedManagementContext context, String targetRef) {
        return target(context.tenantRef(), context.workspaceRef(), targetRef, context.principalRef());
    }

    private GrantMutationReply commit(TrustedManagementContext context, String operation, String requestId,
                                      String digest, String grantRef, String outcome) {
        String changeRef = Ids.next("gchg");
        String now = clock.instant().toString();
        String dedupeKey = dedupeKey(context, operation, requestId);
        db.tx(connection -> {
            db.update("""
                INSERT OR IGNORE INTO l2_grant_requests(dedupe_key, request_id, operation, request_digest,
                  change_ref, outcome, grant_ref, committed_at) VALUES(?,?,?,?,?,?,?,?)
                """, dedupeKey, requestId, operation, digest, changeRef, outcome, grantRef, now);
            return null;
        });
        remember(context, operation, requestId, grantRef);
        return new GrantMutationReply.Committed(new GrantMutationResult(changeRef, outcome,
            view(grant(grantRef)), Instant.parse(now), false));
    }

    private GrantMutationReply replay(TrustedManagementContext context, String operation, String requestId,
                                      String digest) {
        String key = dedupeKey(context, operation, requestId);
        String stored = db.queryOne("SELECT request_digest FROM l2_grant_requests WHERE dedupe_key=?",
            rs -> Db.str(rs, "request_digest"), key);
        if (stored == null) {
            return null;
        }
        if (!stored.equals(digest)) {
            return rejected(ChannelErrorCode.REQUEST_CONFLICT, "requestId was reused with different content");
        }
        String grantRef = db.queryOne("SELECT grant_ref FROM l2_grant_requests WHERE dedupe_key=?",
            rs -> Db.str(rs, "grant_ref"), key);
        String outcome = db.queryOne("SELECT outcome FROM l2_grant_requests WHERE dedupe_key=?",
            rs -> Db.str(rs, "outcome"), key);
        String changeRef = db.queryOne("SELECT change_ref FROM l2_grant_requests WHERE dedupe_key=?",
            rs -> Db.str(rs, "change_ref"), key);
        String committedAt = db.queryOne("SELECT committed_at FROM l2_grant_requests WHERE dedupe_key=?",
            rs -> Db.str(rs, "committed_at"), key);
        GrantRow row = grant(grantRef);
        if (row == null || !inDomain(context, row) || !ownsTarget(context, row.targetRef()))
            return rejected(ChannelErrorCode.PERMISSION_DENIED, "grant result is no longer visible");
        String snapshot = db.queryOne("SELECT snapshot_json FROM l2_grant_snapshots WHERE request_key=?",
            rs -> rs.getString(1), key);
        if (snapshot == null) return new GrantMutationReply.Unknown(requestId,
            ChannelError.of(ChannelErrorCode.OUTCOME_UNKNOWN, "legacy result lacks an immutable snapshot"));
        return new GrantMutationReply.Committed(new GrantMutationResult(changeRef, outcome, readView(Json.read(snapshot)),
            Instant.parse(committedAt), false));
    }

    private GrantView view(GrantRow row) {
        return new GrantView(row.grantRef(), new GrantSpec(terms(Json.read(row.termsJson())),
            row.granteePrincipalRef(), new GrantManagement.GrantResource(row.targetRef(),
            row.resourceEpoch(), row.taskRef())), row.revision(), row.state(), row.createdBy(),
            row.updatedBy(), row.createdAt(), row.updatedAt(), row.revokedAt());
    }

    // ---- validation and serialization ------------------------------------------------

    private String validate(TrustedManagementContext context, GrantSpec spec) {
        requireText(context, spec.granteePrincipalRef(), "grantee");
        if (spec.terms() == null || spec.resource() == null) {
            return "grant resource is required";
        }
        requireText(context, spec.resource().targetRef(), "targetRef");
        return validateTerms(context, spec.terms(), spec.resource().resourceEpoch(), spec.resource().taskRef());
    }

    private String validateTerms(TrustedManagementContext context, GrantTerms terms, long resourceEpoch,
                                 String taskRef) {
        if (terms == null || terms.permissions().isEmpty()) {
            return "permissions must be non-empty";
        }
        if (terms.validFrom() == null || terms.validUntil() == null
            || !terms.validFrom().isBefore(terms.validUntil())) {
            return "validFrom must be earlier than validUntil";
        }
        if (!terms.validUntil().isAfter(clock.instant())) {
            return "validUntil must be in the future";
        }
        Set<String> actions = new HashSet<>();
        for (GrantPermission permission : terms.permissions()) {
            if (!actions.add(permission.action())) {
                return "duplicate action: " + permission.action();
            }
            if (permission instanceof GrantManagement.InteractionRespond respond
                && (respond.responses().isEmpty() || new HashSet<>(respond.responses()).size()
                    != respond.responses().size())) {
                return "interaction.respond responses must be non-empty and unique";
            }
            if (permission instanceof GrantManagement.InteractionRespond respond
                && (!Set.of("approve", "reject", "answer").containsAll(respond.responses())
                    || (respond.allowArgumentEdit() && !respond.responses().contains("approve"))))
                return "invalid interaction response or argument edit permission";
            if (permission instanceof GrantManagement.EventsObserve observe && (observe.eventTypes().isEmpty()
                || new HashSet<>(observe.eventTypes()).size() != observe.eventTypes().size())) {
                return "events.observe eventTypes must be non-empty";
            }
        }
        if (resourceEpoch < 1) {
            return "resourceEpoch must be >= 1";
        }
        if (taskRef != null && (actions.contains("target.read") || actions.contains("session.select")
            || actions.contains("input.submit") || actions.contains("history.read"))) {
            return "task grants cannot include target-wide actions";
        }
        return null;
    }

    private JsonNode specJson(GrantSpec spec) {
        ObjectNode node = Json.obj();
        node.put("grantee", spec.granteePrincipalRef());
        ObjectNode resource = node.putObject("resource");
        resource.put("targetRef", spec.resource().targetRef());
        resource.put("resourceEpoch", spec.resource().resourceEpoch());
        if (spec.resource().taskRef() != null) {
            resource.put("taskRef", spec.resource().taskRef());
        }
        node.set("terms", termsJson(spec.terms()));
        return node;
    }

    private JsonNode termsJson(GrantTerms terms) {
        ObjectNode node = Json.obj();
        var validFrom = node.put("validFrom", terms.validFrom() == null ? "" : terms.validFrom().toString());
        node.put("validUntil", terms.validUntil() == null ? "" : terms.validUntil().toString());
        ArrayNode permissions = node.putArray("permissions");
        for (GrantPermission permission : terms.permissions().stream().sorted(java.util.Comparator.comparing(GrantPermission::action)).toList()) {
            ObjectNode item = permissions.addObject();
            item.put("action", permission.action());
            if (permission instanceof GrantManagement.InteractionRespond respond) {
                ArrayNode responses = item.putArray("responses");
                respond.responses().stream().sorted().forEach(responses::add);
                item.put("allowArgumentEdit", respond.allowArgumentEdit());
            }
            if (permission instanceof GrantManagement.EventsObserve observe) {
                ArrayNode types = item.putArray("eventTypes");
                observe.eventTypes().stream().sorted().forEach(type -> types.add(type.wire()));
            }
        }
        return node;
    }

    private GrantTerms terms(JsonNode node) {
        List<GrantPermission> permissions = new ArrayList<>();
        for (JsonNode item : node.path("permissions")) {
            String action = item.path("action").asText();
            permissions.add(switch (action) {
                case "target.read" -> new GrantManagement.TargetRead();
                case "history.read" -> new GrantManagement.HistoryRead();
                case "session.select" -> new GrantManagement.SessionSelect();
                case "input.submit" -> new GrantManagement.InputSubmit();
                case "task.read" -> new GrantManagement.TaskRead();
                case "task.cancel" -> new GrantManagement.TaskCancel();
                case "interaction.respond" -> new GrantManagement.InteractionRespond(
                    strings(item.path("responses")), item.path("allowArgumentEdit").asBoolean(false));
                case "events.observe" -> new GrantManagement.EventsObserve(events(item.path("eventTypes")));
                default -> throw new IllegalStateException("unknown stored grant action: " + action);
            });
        }
        return new GrantTerms(permissions, Instant.parse(node.path("validFrom").asText()),
            Instant.parse(node.path("validUntil").asText()));
    }

    private List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.asText()));
        return values;
    }

    private List<EventType> events(JsonNode array) {
        List<EventType> values = new ArrayList<>();
        array.forEach(item -> values.add(EventType.parse(item.asText())));
        return values;
    }

    private String dedupeKey(TrustedManagementContext context, String operation, String requestId) {
        return Digests.sha256(context.tenantRef() + "\u0000" + context.workspaceRef() + "\u0000"
            + context.principalRef() + "\u0000" + operation + "\u0000" + requestId);
    }

    private String digest(String... parts) {
        return Digests.sha256(String.join("\u0000", parts));
    }

    private static void requireText(TrustedManagementContext context, String value, String field) {
        if (value == null || value.isBlank()) {
            throw new com.netlivecowork.channel.contract.ChannelContractException(
                ChannelErrorCode.INVALID_ARGUMENT, field + " is required");
        }
    }

    private static GrantMutationReply rejected(ChannelErrorCode code, String message) {
        return new GrantMutationReply.Rejected(ChannelError.of(code, message), null);
    }

    private record Authority(String tenant, String workspace, String owner, long epoch, long generation,
                             Instant validUntil, String delegable) {}

    private Authority authority(String target) {
        return db.queryOne("SELECT * FROM l2_targets WHERE target_ref=?", rs -> new Authority(
            rs.getString("tenant_ref"), rs.getString("workspace_ref"), rs.getString("owner_principal_ref"),
            rs.getLong("resource_epoch"), rs.getLong("authority_generation"),
            rs.getString("evidence_valid_until") == null ? Instant.EPOCH : Instant.parse(rs.getString("evidence_valid_until")),
            rs.getString("delegable_terms")), target);
    }

    private long generation(String target) { return authority(target).generation(); }

    private boolean target(String tenant, String workspace, String target, String owner) {
        Authority a = authority(target);
        return a != null && java.util.Objects.equals(a.tenant(), tenant)
            && java.util.Objects.equals(a.workspace(), workspace) && a.owner() != null
            && (owner == null || a.owner().equals(owner)) && clock.instant().isBefore(a.validUntil());
    }

    private boolean inDomain(TrustedManagementContext c, GrantRow row) {
        return row.tenantRef().equals(c.tenantRef()) && row.workspaceRef().equals(c.workspaceRef());
    }

    private void requireContext(TrustedManagementContext c) {
        if (c == null) throw new com.netlivecowork.channel.contract.ChannelContractException(
            ChannelErrorCode.PERMISSION_DENIED, "authenticated management context required");
        requireText(c, c.tenantRef(), "tenantRef"); requireText(c, c.workspaceRef(), "workspaceRef");
        requireText(c, c.principalRef(), "principalRef"); requireText(c, c.authenticationRef(), "authenticationRef");
    }

    private boolean mayGrant(TrustedManagementContext c, GrantSpec spec) {
        if (!ownsTarget(c, spec.resource().targetRef())) return false;
        Authority a = authority(spec.resource().targetRef());
        if (a.epoch() != spec.resource().resourceEpoch() || a.delegable() == null) return false;
        GrantTerms limit = terms(Json.read(a.delegable()));
        if (spec.terms().validFrom().isBefore(limit.validFrom()) || spec.terms().validUntil().isAfter(limit.validUntil())
            || !covers(limit, spec.terms().permissions())) return false;
        if (spec.resource().taskRef() != null) {
            Integer found = db.queryOne("SELECT 1 FROM l2_tasks WHERE task_ref=? AND target_ref=? AND tenant_ref=? AND workspace_ref=?",
                rs -> rs.getInt(1), spec.resource().taskRef(), spec.resource().targetRef(), c.tenantRef(), c.workspaceRef());
            if (found == null) return false;
        }
        return true;
    }

    private boolean basisCurrent(GrantRow row) {
        Authority a = authority(row.targetRef());
        Long g = db.queryOne("SELECT authority_generation FROM l2_grants WHERE grant_ref=?", rs -> rs.getLong(1), row.grantRef());
        return target(row.tenantRef(), row.workspaceRef(), row.targetRef(), null)
            && a.epoch() == row.resourceEpoch() && g != null && g == a.generation();
    }

    private boolean active(GrantTerms terms) {
        return !clock.instant().isBefore(terms.validFrom()) && clock.instant().isBefore(terms.validUntil());
    }

    private boolean covers(GrantTerms terms, List<GrantPermission> required) {
        return required.stream().allMatch(use -> terms.permissions().stream().anyMatch(p -> {
            if (!p.action().equals(use.action())) return false;
            if (use instanceof GrantManagement.InteractionRespond r) {
                var allowed = (GrantManagement.InteractionRespond) p;
                return allowed.responses().containsAll(r.responses()) && (!r.allowArgumentEdit() || allowed.allowArgumentEdit());
            }
            if (use instanceof GrantManagement.EventsObserve e)
                return ((GrantManagement.EventsObserve) p).eventTypes().containsAll(e.eventTypes());
            return true;
        }));
    }

    private GrantPermission permission(String action) {
        return switch (action) {
            case "target.read" -> new GrantManagement.TargetRead();
            case "history.read" -> new GrantManagement.HistoryRead();
            case "session.select" -> new GrantManagement.SessionSelect();
            case "input.submit" -> new GrantManagement.InputSubmit();
            case "task.read" -> new GrantManagement.TaskRead();
            case "task.cancel" -> new GrantManagement.TaskCancel();
            default -> throw new IllegalArgumentException("a scoped permission is required");
        };
    }

    boolean canConfigureTarget(String tenant,String workspace,String principal,String target) {
        return authorizeResource(tenant,workspace,principal,target,null,List.of(new GrantManagement.TargetRead()))!=null;
    }

    private ObjectNode authorize(String tenant, String workspace, String principal, String target,
                                 String task, List<GrantPermission> required) {
        if(principal==null || !personalSessions.allows(tenant,workspace,principal,target)) return null;
        return authorizeResource(tenant,workspace,principal,target,task,required);
    }

    private ObjectNode authorizeResource(String tenant,String workspace,String principal,String target,
                                        String task,List<GrantPermission> required) {
        if (principal == null || !target(tenant, workspace, target, null)) return null;
        Authority a = authority(target);
        ObjectNode basis = Json.obj().put("tenant", tenant).put("workspace", workspace).put("principal", principal)
            .put("target", target).put("epoch", a.epoch()).put("generation", a.generation())
            .put("issuedAt", clock.instant().toString());
        basis.put("personalSelectionGeneration",personalSessions.generation(tenant,workspace,principal,target));
        if (task != null) basis.put("task", task);
        basis.set("use", termsJson(new GrantTerms(required, Instant.EPOCH, Instant.parse("9999-01-01T00:00:00Z"))));
        if (principal.equals(a.owner())) return basis.put("owner", principal);
        for (String ref : db.query("SELECT grant_ref FROM l2_grants WHERE tenant_ref=? AND workspace_ref=? AND target_ref=? AND grantee_principal_ref=? AND state='enabled' ORDER BY grant_ref",
            rs -> rs.getString(1), tenant, workspace, target, principal)) {
            GrantRow row = grant(ref);
            GrantTerms terms = terms(Json.read(row.termsJson()));
            if (basisCurrent(row) && (row.taskRef() == null || row.taskRef().equals(task))
                && active(terms) && covers(terms, required))
                return basis.put("grant", ref).put("revision", row.revision());
        }
        return null;
    }

    void requireAction(String tenant, String workspace, String principal, String target, String task,
                       List<GrantPermission> required) {
        if (authorize(tenant, workspace, principal, target, task, required) == null)
            throw new com.netlivecowork.channel.contract.ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,
                "resource action is not authorized");
    }

    private List<GrantPermission> required(com.netlivecowork.channel.contract.CommandPacket p) {
        return switch (p.type()) {
            case INPUT_SUBMIT, SESSION_CREATE, SESSION_RESUME -> List.of(new GrantManagement.TargetRead(), new GrantManagement.InputSubmit());
            case SESSION_GET -> List.of(new GrantManagement.TargetRead());
            case TASK_GET -> List.of(new GrantManagement.TaskRead());
            case TASK_CANCEL -> List.of(new GrantManagement.TaskCancel());
            case INTERACTION_RESPOND -> {
                var r = ((com.netlivecowork.channel.contract.CommandPayload.InteractionRespond)p.payload()).payload().response();
                yield List.of(new GrantManagement.InteractionRespond(List.of(r.kind()),
                    r instanceof com.netlivecowork.channel.contract.InteractionResponse.Approve a && a.modifiedArguments() != null));
            }
            default -> throw new com.netlivecowork.channel.contract.ChannelContractException(ChannelErrorCode.UNSUPPORTED_CAPABILITY, "unsupported authorization action");
        };
    }

    void capture(com.netlivecowork.channel.contract.CommandPacket p) {
        ObjectNode b = authorize(p.context().tenantRef(), p.context().workspaceRef(), p.actor().principalRef(),
            p.context().targetRef(), p.context().taskRef(), required(p));
        if (b == null) throw new com.netlivecowork.channel.contract.ChannelContractException(
            ChannelErrorCode.PERMISSION_DENIED, "resource action is not authorized");
        db.update("INSERT INTO l2_authorization_basis(operation_ref,basis_json) VALUES(?,?)", p.context().operationRef(), Json.write(b));
    }

    void captureHistory(String operationRef, com.netlivecowork.channel.contract.TrustedCall call, String target) {
        var basis = authorize(call.tenantRef(), call.workspaceRef(), call.principalRef(), target, null,
            List.of(new GrantManagement.TargetRead(), new GrantManagement.HistoryRead()));
        if (basis == null) throw new com.netlivecowork.channel.contract.ChannelContractException(
            ChannelErrorCode.PERMISSION_DENIED, "history.read is not authorized for this session");
        db.update("INSERT INTO l2_authorization_basis(operation_ref,basis_json) VALUES(?,?)", operationRef, Json.write(basis));
    }

    boolean current(String operationRef) {
        String stored = db.queryOne("SELECT basis_json FROM l2_authorization_basis WHERE operation_ref=?", rs -> rs.getString(1), operationRef);
        if (stored == null) return false; // Legacy work cannot acquire a new grant on recovery.
        JsonNode b = Json.read(stored);
        String target = b.path("target").asText();
        Authority a = authority(target);
        String tenant=b.path("tenant").asText(),workspace=b.path("workspace").asText(),principal=b.path("principal").asText();
        if(!personalSessions.allows(tenant,workspace,principal,target)
            || personalSessions.generation(tenant,workspace,principal,target)!=b.path("personalSelectionGeneration").asLong(0)) return false;
        if (!target(b.path("tenant").asText(), b.path("workspace").asText(), target, null)
            || a.epoch() != b.path("epoch").asLong() || a.generation() != b.path("generation").asLong()) return false;
        if (b.has("owner")) return a.owner().equals(b.path("owner").asText());
        GrantRow row = grant(b.path("grant").asText());
        if (row == null || !"enabled".equals(row.state()) || !basisCurrent(row)) return false;
        List<GrantPermission> use = terms(b.path("use")).permissions();
        List<String> history = db.query("SELECT terms_json FROM l2_grant_history WHERE grant_ref=? AND revision>=? ORDER BY revision",
            rs -> rs.getString(1), row.grantRef(), b.path("revision").asLong());
        Instant issuedAt = Instant.parse(b.path("issuedAt").asText(Instant.EPOCH.toString()));
        return !history.isEmpty() && history.stream().allMatch(t -> {
            GrantTerms version = terms(Json.read(t));
            return !issuedAt.isBefore(version.validFrom()) && active(version) && covers(version, use);
        });
    }

    /** Authorization and durable attempt claim compete with revocation in one short transaction. */
    boolean begin(String operationRef, java.util.function.BooleanSupplier claim) {
        return db.tx(c -> {
            if (!current(operationRef) || !claim.getAsBoolean()) return false;
            db.update("INSERT INTO l2_authorization_attempts(operation_ref,started_at) VALUES(?,?)",
                operationRef, clock.instant().toString());
            return true;
        });
    }

    private void remember(TrustedManagementContext c, String method, String request, String ref) {
        GrantRow row = grant(ref);
        db.update("INSERT OR IGNORE INTO l2_grant_history(grant_ref,revision,terms_json) VALUES(?,?,?)", ref, row.revision(), row.termsJson());
        ObjectNode v = Json.obj().put("grantRef", ref).put("revision", row.revision()).put("state", row.state())
            .put("createdBy", row.createdBy()).put("updatedBy", row.updatedBy())
            .put("createdAt", row.createdAt().toString()).put("updatedAt", row.updatedAt().toString());
        if (row.revokedAt() != null) v.put("revokedAt", row.revokedAt().toString());
        v.set("spec", specJson(view(row).spec()));
        db.update("INSERT INTO l2_grant_snapshots(request_key,snapshot_json) VALUES(?,?)", dedupeKey(c,method,request), Json.write(v));
    }

    private GrantView readView(JsonNode v) {
        JsonNode s = v.path("spec"), r = s.path("resource");
        GrantSpec spec = new GrantSpec(terms(s.path("terms")), s.path("grantee").asText(),
            new GrantManagement.GrantResource(r.path("targetRef").asText(), r.path("resourceEpoch").asLong(), r.has("taskRef") ? r.path("taskRef").asText() : null));
        return new GrantView(v.path("grantRef").asText(), spec, v.path("revision").asLong(), v.path("state").asText(),
            v.path("createdBy").asText(), v.path("updatedBy").asText(), Instant.parse(v.path("createdAt").asText()),
            Instant.parse(v.path("updatedAt").asText()), v.has("revokedAt") ? Instant.parse(v.path("revokedAt").asText()) : null);
    }


    boolean beginDelivery(String delivery, String recipient) {
        return db.tx(c -> {
            if (!currentDelivery(delivery, recipient)) return false;
            db.update("INSERT INTO l2_authorization_attempts(operation_ref,started_at) VALUES(?,?)",
                "delivery:" + delivery, clock.instant().toString());
            return true;
        });
    }

    boolean currentDelivery(String delivery, String recipient) {
            for (String operation : db.query("""
                SELECT rr.operation_ref FROM l2_outbox_relations r
                JOIN l2_reply_relations rr ON rr.relation_id=r.relation_ref
                WHERE r.delivery_id=? AND r.relation_type='REPLY' AND r.active=1
                  AND rr.authorized_by=? AND rr.status='ACTIVE' AND rr.shared=0
                """, rs -> rs.getString(1), delivery, recipient)) {
                if (current(operation)) {
                    return true;
                }
            }
            return false;
    }
}
