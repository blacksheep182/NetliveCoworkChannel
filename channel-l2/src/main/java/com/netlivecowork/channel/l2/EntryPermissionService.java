package com.netlivecowork.channel.l2;

import com.fasterxml.jackson.databind.JsonNode;
import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.port.BackendAccessPort;
import com.netlivecowork.channel.infra.Db;
import java.util.function.Supplier;

/** L2 retains the reference and resolves it through L3 when it authorizes work. */
final class EntryPermissionService {
    record Reference(String serviceRef, String permissionRef) {
        String stored() { return "host/" + serviceRef + "/" + permissionRef; }
    }
    private record Binding(String policy, boolean enabled) {}
    private record Scope(String entryRef, Reference authority, JsonNode snapshot) {}

    private final Db db;
    private final BackendAccessPort backend;
    private final ThreadLocal<Scope> operation = new ThreadLocal<>();

    EntryPermissionService(Db db, BackendAccessPort backend) {
        this.db = db;
        this.backend = backend;
    }

    static Reference reference(String serviceRef, String permissionRef) {
        if (!serviceRef.matches("[A-Za-z0-9._~-]{1,128}") || !permissionRef.matches("ep-[a-f0-9]{32}"))
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "Invalid entry permission reference");
        return new Reference(serviceRef, permissionRef);
    }

    private Binding binding(String entryRef) {
        if (entryRef == null) return null;
        return db.queryOne("""
            SELECT channel_policy_ref, enabled FROM l2_entries
            WHERE entry_ref=? AND channel_policy_ref LIKE 'host/%'
            """, row -> new Binding(row.getString(1), row.getBoolean(2)), entryRef);
    }

    boolean managed(String entryRef) {
        Scope scope = operation.get();
        return (scope != null && scope.entryRef().equals(entryRef)) || binding(entryRef) != null;
    }

    private Reference reference(Binding binding) {
        if (binding != null && binding.enabled()) {
            String[] fields = binding.policy().split("/", -1);
            if (fields.length == 3 && fields[0].equals("host"))
                return reference(fields[1], fields[2]);
        }
        throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED, "The entry permission reference is unavailable");
    }

    JsonNode check(String entryRef, String target, String workspace, Long generation, Long targetGeneration) {
        Scope scope = operation.get();
        if (scope != null && scope.entryRef().equals(entryRef)) {
            JsonNode snapshot = scope.snapshot();
            long grantedGeneration = snapshot.path("generation").asLong();
            JsonNode targetEvidence = target == null ? null : snapshot.path("targets").path(target);
            long grantedTargetGeneration = targetEvidence == null ? 0 : targetEvidence.asLong();
            boolean workspaceGranted = workspace == null;
            if (!workspaceGranted)
                for (JsonNode candidate : snapshot.path("workspaces"))
                    if (workspace.equals(candidate.asText())) { workspaceGranted = true; break; }
            if ((generation != null && generation != grantedGeneration)
                    || (targetEvidence != null && !targetEvidence.isIntegralNumber())
                    || (targetGeneration != null && targetGeneration != grantedTargetGeneration) || !workspaceGranted)
                throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED, "The CoWork entry does not authorize this resource");
            return Json.obj().put("permissionRef", scope.authority().permissionRef()).put("generation", grantedGeneration)
                .put("targetGeneration", grantedTargetGeneration)
                .put("channelGeneration", snapshot.path("channelGenerations")
                    .path(workspace == null ? target : "workspace:" + workspace).asLong(0));
        }
        if (db.inTransaction())
            throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE, "The entry requires an authenticated operation scope");
        Reference authority = reference(binding(entryRef));
        var body = Json.obj();
        if (target != null) body.put("targetRef", target);
        if (workspace != null) body.put("workspaceRef", workspace);
        if (generation != null) body.put("generation", generation);
        if (targetGeneration != null) body.put("targetGeneration", targetGeneration);
        var answer = backend.entryPermission(authority.serviceRef(), authority.permissionRef(), "check", body);
        if (!authority.permissionRef().equals(answer.path("permissionRef").asText())
                || !answer.path("generation").isIntegralNumber() || !answer.path("targetGeneration").isIntegralNumber())
            throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE, "The CoWork permission response is invalid");
        return answer;
    }

    <T> T scoped(String entryRef, boolean refresh, Supplier<T> work) {
        Scope previous = operation.get();
        if (!refresh && previous != null && previous.entryRef().equals(entryRef)) return work.get();
        Binding binding = binding(entryRef);
        if (binding == null) return work.get();
        if (db.inTransaction())
            throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE, "Entry verification requires an independent request");
        Reference authority = reference(binding);
        JsonNode snapshot = backend.entryPermission(authority.serviceRef(), authority.permissionRef(), "snapshot", Json.obj());
        if (!authority.permissionRef().equals(snapshot.path("permissionRef").asText()) || !snapshot.path("generation").isIntegralNumber()
                || !snapshot.path("targets").isObject() || !snapshot.path("workspaces").isArray())
            throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE, "The CoWork operation scope is invalid");
        operation.set(new Scope(entryRef, authority, snapshot));
        try {
            return work.get();
        } finally {
            if (previous == null) operation.remove();
            else operation.set(previous);
        }
    }

    void created(String entryRef, String target, String workspace, long generation) {
        Reference authority = reference(binding(entryRef));
        backend.entryPermission(authority.serviceRef(), authority.permissionRef(), "created-target",
            Json.obj().put("targetRef", target).put("workspaceRef", workspace).put("generation", generation));
    }
}
