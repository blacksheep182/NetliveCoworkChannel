package com.netlivecowork.channel.l2.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.FileMetadata;
import com.netlivecowork.channel.contract.TargetSummary;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.infra.Db;
import com.netlivecowork.channel.infra.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 身份映射、协作范围、范围绑定、逻辑目标、目的地、admission 与文件登记。 */
public final class DirectoryRepository {

    private final Db db;
    private final Clock clock;

    public DirectoryRepository(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    // ---- identity bindings -----------------------------------------------------------

    public String callerFor(String entryRef, long epoch, String subjectKind, String subjectKey) {
        IdentityBinding binding = identity(entryRef, epoch, subjectKind, subjectKey);
        return binding == null ? null : binding.callerRef();
    }

    /** 身份绑定：内部主体 + 绑定版本。绑定版本进入 scope/destination 标识，换绑后旧标识自然失效。 */
    public record IdentityBinding(String callerRef, long revision) {
    }

    public IdentityBinding identity(String entryRef, long epoch, String subjectKind, String subjectKey) {
        return db.queryOne("""
            SELECT caller_ref, binding_revision FROM l2_identity_bindings
            WHERE entry_ref=? AND account_binding_epoch=? AND external_subject_kind=? AND external_subject_key=?
            """, rs -> new IdentityBinding(Db.str(rs, "caller_ref"), rs.getLong("binding_revision")),
            entryRef, epoch, subjectKind, subjectKey);
    }

    /**
     * 幂等绑定：同一身份绑到同一 caller 不升版本；并发重复绑定也只产生一个版本（SM07 §9.3）。
     * 条件 UPDATE 在数据库层判重，避免先读后写的竞态。
     */
    public long bindIdentity(String entryRef, long epoch, String subjectKind, String subjectKey,
                             String callerRef) {
        return db.tx(connection -> upsertBinding(entryRef, epoch, subjectKind, subjectKey, callerRef));
    }

    private long upsertBinding(String entryRef, long epoch, String subjectKind, String subjectKey,
                               String callerRef) {
        db.update("""
            INSERT INTO l2_identity_bindings(entry_ref, account_binding_epoch, external_subject_kind,
              external_subject_key, caller_ref, binding_revision, created_at) VALUES(?,?,?,?,?,?,?)
            ON CONFLICT(entry_ref, account_binding_epoch, external_subject_kind, external_subject_key)
            DO UPDATE SET caller_ref=excluded.caller_ref,
              binding_revision=l2_identity_bindings.binding_revision+1
            WHERE l2_identity_bindings.caller_ref <> excluded.caller_ref
            """,
            entryRef, epoch, subjectKind, subjectKey, callerRef, 1L, clock.instant().toString());
        IdentityBinding current = identity(entryRef, epoch, subjectKind, subjectKey);
        return current == null ? 1L : current.revision();
    }

    /**
     * 原子换绑：撤销原绑定的工作与提升绑定版本在同一事务内提交（SM07 §9.3 的失效屏障）。
     * 任一失败整体回滚，不会出现“目的地已失效但绑定还是旧 caller”的可写窗口。
     */
    public long rebindIdentity(String entryRef, long epoch, String subjectKind, String subjectKey,
                               String callerRef) {
        return db.tx(connection -> {
            IdentityBinding existing = identity(entryRef, epoch, subjectKind, subjectKey);
            if (existing != null && !existing.callerRef().equals(callerRef)) {
                revokeWorkForBinding(entryRef, subjectKind, subjectKey);
            }
            return upsertBinding(entryRef, epoch, subjectKind, subjectKey, callerRef);
        });
    }

    /**
     * 换绑后使原绑定的工作失效：只影响该绑定自身的个人目的地与范围绑定，不按 caller 批量撤销，
     * 也不影响共享 scope/destination（SM07 §9.3）。必须在调用方事务内执行。
     */
    private void revokeWorkForBinding(String entryRef, String subjectKind, String subjectKey) {
        String now = clock.instant().toString();
        db.update("""
            UPDATE l2_destinations SET destination_revision=destination_revision+1, updated_at=?
            WHERE entry_ref=? AND identity_subject_kind=? AND identity_subject_key=? AND caller_ref IS NOT NULL
            """, now, entryRef, subjectKind, subjectKey);
        db.update("""
            DELETE FROM l2_scope_bindings
            WHERE scope_ref IN (SELECT scope_ref FROM l2_scopes
              WHERE entry_ref=? AND identity_subject_kind=? AND identity_subject_key=? AND shared=0)
            """, entryRef, subjectKind, subjectKey);
    }

    /** 换绑账号或身份绑定代次后，旧代次映射全部失效（INV-12、INV-55）。 */
    public void purgeIdentitiesBelowEpoch(String entryRef, long keepEpoch) {
        db.update("DELETE FROM l2_identity_bindings WHERE entry_ref=? AND account_binding_epoch<?",
            entryRef, keepEpoch);
    }

    // ---- scopes ----------------------------------------------------------------------

    public record ScopeRow(String tenantRef, String workspaceRef, String scopeRef, String entryRef,
                           long accountBindingEpoch, String callerRef, String identitySubjectKind,
                           String identitySubjectKey, String namespace, String externalKey,
                           String audience, boolean shared) {
    }

    public void ensureScope(ScopeRow scope) {
        db.update("""
            INSERT INTO l2_scopes(tenant_ref, workspace_ref, scope_ref, entry_ref, account_binding_epoch,
              caller_ref, identity_subject_kind, identity_subject_key, namespace, external_key, audience,
              shared, created_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(tenant_ref, workspace_ref, scope_ref) DO UPDATE SET audience=excluded.audience,
              shared=excluded.shared, caller_ref=COALESCE(excluded.caller_ref, l2_scopes.caller_ref),
              identity_subject_kind=COALESCE(excluded.identity_subject_kind, l2_scopes.identity_subject_kind),
              identity_subject_key=COALESCE(excluded.identity_subject_key, l2_scopes.identity_subject_key)
            """,
            scope.tenantRef(), scope.workspaceRef(), scope.scopeRef(), scope.entryRef(),
            scope.accountBindingEpoch(), scope.callerRef(), scope.identitySubjectKind(),
            scope.identitySubjectKey(), scope.namespace(), scope.externalKey(), scope.audience(),
            scope.shared(), clock.instant().toString());
    }

    public ScopeRow scope(String tenantRef, String workspaceRef, String scopeRef) {
        return db.queryOne("""
            SELECT * FROM l2_scopes WHERE tenant_ref=? AND workspace_ref=? AND scope_ref=?
            """, rs -> new ScopeRow(Db.str(rs, "tenant_ref"), Db.str(rs, "workspace_ref"),
            Db.str(rs, "scope_ref"), Db.str(rs, "entry_ref"), rs.getLong("account_binding_epoch"),
            Db.str(rs, "caller_ref"), Db.str(rs, "identity_subject_kind"),
            Db.str(rs, "identity_subject_key"), Db.str(rs, "namespace"), Db.str(rs, "external_key"),
            Db.str(rs, "audience"), Db.bool(rs, "shared")), tenantRef, workspaceRef, scopeRef);
    }

    // ---- scope bindings --------------------------------------------------------------

    public record BindingRow(String tenantRef, String workspaceRef, String scopeRef, String targetRef,
                             long revision, String updatedBy, String sessionWorkspaceRef) {
        public BindingRow { targetRef = targetRef == null || targetRef.isBlank() ? null : targetRef; }
    }

    public BindingRow binding(String tenantRef, String workspaceRef, String scopeRef) {
        return db.queryOne("""
            SELECT * FROM l2_scope_bindings WHERE tenant_ref=? AND workspace_ref=? AND scope_ref=?
            """, rs -> new BindingRow(Db.str(rs, "tenant_ref"), Db.str(rs, "workspace_ref"),
            Db.str(rs, "scope_ref"), Db.str(rs, "target_ref"), rs.getLong("revision"),
            Db.str(rs, "updated_by"), Db.str(rs,"session_workspace_ref")), tenantRef, workspaceRef, scopeRef);
    }

    /** 原子替换绑定：版本不符返回 false，由调用方返回 BINDING_CONFLICT。 */
    public boolean compareAndSetBinding(String tenantRef, String workspaceRef, String scopeRef,
                                        String targetRef, long expectedRevision, String updatedBy) {
        BindingRow current = binding(tenantRef,workspaceRef,scopeRef);
        return compareAndSetSelection(tenantRef,workspaceRef,scopeRef,current==null?null:current.sessionWorkspaceRef(),targetRef,expectedRevision,updatedBy);
    }

    public boolean compareAndSetSelection(String tenantRef, String workspaceRef, String scopeRef,
            String sessionWorkspaceRef, String targetRef, long expectedRevision, String updatedBy) {
        return db.tx(connection -> {
            BindingRow existing = binding(tenantRef, workspaceRef, scopeRef);
            String now = clock.instant().toString();
            if (existing == null) {
                if (expectedRevision != 0) {
                    return false;
                }
                db.update("""
                    INSERT INTO l2_scope_bindings(tenant_ref, workspace_ref, scope_ref, target_ref, revision,
                      updated_by, updated_at, session_workspace_ref) VALUES(?,?,?,?,?,?,?,?)
                    """, tenantRef, workspaceRef, scopeRef, targetRef==null?"":targetRef, 1L, updatedBy, now, sessionWorkspaceRef);
                return true;
            }
            if (existing.revision() != expectedRevision) {
                return false;
            }
            int rows = db.update("""
                UPDATE l2_scope_bindings SET target_ref=?, revision=revision+1, updated_by=?, updated_at=?, session_workspace_ref=?
                WHERE tenant_ref=? AND workspace_ref=? AND scope_ref=? AND revision=?
                """, targetRef==null?"":targetRef, updatedBy, now, sessionWorkspaceRef, tenantRef, workspaceRef, scopeRef, expectedRevision);
            return rows == 1;
        });
    }

    public List<String> navigationList(String scope,String kind) {
        String json=db.queryOne("SELECT items_json FROM l2_navigation_lists WHERE scope_ref=? AND kind=?",r->r.getString(1),scope,kind);
        if(json==null)return List.of();
        var result=new java.util.ArrayList<String>(); for(var value:Json.read(json))result.add(value.asText());
        return List.copyOf(result);
    }

    public void rememberNavigationList(String scope, String kind, List<String> refs) {
        db.update("""
            INSERT INTO l2_navigation_lists(scope_ref,kind,items_json) VALUES(?,?,?)
            ON CONFLICT(scope_ref,kind) DO UPDATE SET items_json=excluded.items_json
            """, scope, kind, Json.write(Json.mapper().valueToTree(refs)));
    }

    // ---- execution targets -----------------------------------------------------------

    public record TargetRow(String targetRef, String tenantRef, String workspaceRef, String agentRef,
                            String label, List<String> capabilities, TargetSummary.Availability availability,
                            String resourceRef, long bindingRevision) {

        public TargetSummary summary() {
            return new TargetSummary(targetRef, agentRef, label, capabilities, availability);
        }
    }

    public void upsertTarget(TargetRow row) {
        db.update("""
            INSERT INTO l2_targets(target_ref, tenant_ref, workspace_ref, agent_ref, label, capabilities,
              availability, resource_ref, binding_revision, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(target_ref) DO UPDATE SET agent_ref=excluded.agent_ref, label=excluded.label,
              capabilities=excluded.capabilities, availability=excluded.availability,
              resource_ref=excluded.resource_ref, binding_revision=excluded.binding_revision,
              updated_at=excluded.updated_at
            """,
            row.targetRef(), row.tenantRef(), row.workspaceRef(), row.agentRef(), row.label(),
            Json.write(Json.mapper().valueToTree(row.capabilities())), row.availability().name(),
            row.resourceRef(), row.bindingRevision(), clock.instant().toString());
    }

    public TargetRow target(String targetRef) {
        return db.queryOne("SELECT * FROM l2_targets WHERE target_ref=?", this::mapTarget, targetRef);
    }

    public List<TargetRow> targets(String tenantRef, String workspaceRef) {
        return db.query("SELECT * FROM l2_targets WHERE tenant_ref=? AND workspace_ref=? ORDER BY label",
            this::mapTarget, tenantRef, workspaceRef);
    }

    private TargetRow mapTarget(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<String> capabilities = new ArrayList<>();
        for (JsonNode item : Json.read(Db.str(rs, "capabilities"))) {
            capabilities.add(item.asText());
        }
        return new TargetRow(Db.str(rs, "target_ref"), Db.str(rs, "tenant_ref"), Db.str(rs, "workspace_ref"),
            Db.str(rs, "agent_ref"), Db.str(rs, "label"), capabilities,
            TargetSummary.Availability.valueOf(Db.str(rs, "availability")), Db.str(rs, "resource_ref"),
            rs.getLong("binding_revision"));
    }

    // ---- destinations ----------------------------------------------------------------

    /** 归属事实由 L3 验证登记后写入内部视图（v2.11 §2.4），不进入公开 TargetSummary。 */
    public void setTargetOwner(String targetRef, String ownerPrincipalRef) {
        db.update("UPDATE l2_targets SET owner_principal_ref=?, authority_generation=authority_generation+1, evidence_valid_until=NULL, delegable_terms=NULL WHERE target_ref=?", ownerPrincipalRef,
            targetRef);
    }

    /** 后端核验后的归属证据：版本、资源代次与当前记录（applyOwnership 的比较基线）。 */
    public record OwnershipEvidence(String tenantRef, String workspaceRef, String ownerPrincipalRef,
                                    long resourceEpoch, long evidenceRevision, Instant evidenceValidUntil) {
    }

    public OwnershipEvidence ownershipEvidence(String targetRef) {
        return db.queryOne("""
            SELECT tenant_ref, workspace_ref, owner_principal_ref, resource_epoch, evidence_revision,
              evidence_valid_until FROM l2_targets WHERE target_ref=?
            """, rs -> new OwnershipEvidence(Db.str(rs, "tenant_ref"), Db.str(rs, "workspace_ref"),
            Db.str(rs, "owner_principal_ref"), rs.getLong("resource_epoch"), rs.getLong("evidence_revision"),
            Db.str(rs, "evidence_valid_until") == null ? null : Instant.parse(Db.str(rs, "evidence_valid_until"))),
            targetRef);
    }

    /**
     * 应用后端核验的归属事实（v2.11 §2.6、SM07 §9.5）：revision 单调，旧事实不回滚；
     * 版本前进或归属/代次变化时抬 authority_generation 并清空委派依据，旧 Grant 与在途工作失效
     * （owner A→B→A 同样失效，不能只比较最终 owner 字符串）；同版本同归属的普通刷新只延长
     * 证据有效期，不误撤现有授权。
     *
     * @return 本次是否写入；事实落后于当前记录或目标不在本资源域时返回 false
     */
    public boolean applyOwnership(String targetRef, String tenantRef, String workspaceRef,
                                  String ownerPrincipalRef, long resourceEpoch, long revision,
                                  Instant validUntil) {
        return db.tx(connection -> {
            OwnershipEvidence current = ownershipEvidence(targetRef);
            if (current == null || !java.util.Objects.equals(current.tenantRef(), tenantRef)
                || !java.util.Objects.equals(current.workspaceRef(), workspaceRef)) {
                return false;
            }
            if (revision < current.evidenceRevision()) {
                return false;
            }
            boolean sameBasis = revision == current.evidenceRevision()
                && java.util.Objects.equals(ownerPrincipalRef, current.ownerPrincipalRef())
                && resourceEpoch == current.resourceEpoch();
            if (sameBasis) {
                // 纯刷新：只在新的有效期更晚时延长，缓存不回退也不自行续权。
                if (current.evidenceValidUntil() == null || validUntil.isAfter(current.evidenceValidUntil())) {
                    db.update("UPDATE l2_targets SET evidence_valid_until=? WHERE target_ref=?",
                        validUntil.toString(), targetRef);
                    return true;
                }
                return false;
            }
            db.update("""
                UPDATE l2_targets SET owner_principal_ref=?, resource_epoch=?, evidence_revision=?,
                  evidence_valid_until=?, authority_generation=authority_generation+1, delegable_terms=NULL
                WHERE target_ref=?
                """, ownerPrincipalRef, resourceEpoch, revision, validUntil.toString(), targetRef);
            return true;
        });
    }

    /** 是否存在到期前需要重取归属事实的目标（含手动交接后缺有效期的记录）。 */
    public boolean ownershipRefreshDue(String tenantRef, String workspaceRef, Instant horizon) {
        Integer due = db.queryOne("""
            SELECT 1 FROM l2_targets WHERE tenant_ref=? AND workspace_ref=?
              AND (owner_principal_ref IS NULL OR evidence_valid_until IS NULL OR evidence_valid_until<?) LIMIT 1
            """, rs -> rs.getInt(1), tenantRef, workspaceRef, horizon.toString());
        return due != null;
    }

    public String targetOwner(String targetRef) {
        return db.queryOne("SELECT owner_principal_ref FROM l2_targets WHERE target_ref=?",
            rs -> Db.str(rs, "owner_principal_ref"), targetRef);
    }

    public record DestinationRow(String destinationRef, String tenantRef, String workspaceRef, String entryRef,
                                 long accountBindingEpoch, String callerRef, String identitySubjectKind,
                                 String identitySubjectKey, String audience, long destinationRevision,
                                 JsonNode protocolData) {
    }

    public void upsertDestination(DestinationRow row) {
        db.update("""
            INSERT INTO l2_destinations(destination_ref, tenant_ref, workspace_ref, entry_ref,
              account_binding_epoch, caller_ref, identity_subject_kind, identity_subject_key, audience,
              destination_revision, protocol_data, created_at, updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(destination_ref) DO UPDATE SET audience=excluded.audience,
              destination_revision=excluded.destination_revision, protocol_data=excluded.protocol_data,
              caller_ref=COALESCE(excluded.caller_ref, l2_destinations.caller_ref),
              identity_subject_kind=COALESCE(excluded.identity_subject_kind,
                l2_destinations.identity_subject_kind),
              identity_subject_key=COALESCE(excluded.identity_subject_key, l2_destinations.identity_subject_key),
              updated_at=excluded.updated_at
            """,
            row.destinationRef(), row.tenantRef(), row.workspaceRef(), row.entryRef(),
            row.accountBindingEpoch(), row.callerRef(), row.identitySubjectKind(),
            row.identitySubjectKey(), row.audience(), row.destinationRevision(),
            Json.write(row.protocolData()), clock.instant().toString(), clock.instant().toString());
    }

    /**
     * 管理端登记/更新目的地：版本单调递增，已有记录必须带 expectedRevision；
     * 绝不把版本重置为 1，否则旧积压会重新通过版本检查（INV-13）。
     *
     * @param expectedRevision 已有记录必须匹配的当前版本；新记录传 0
     * @return 写入后的目的地版本
     */
    public long registerDestination(String destinationRef, String tenantRef, String workspaceRef,
                                    String entryRef, long accountBindingEpoch, String audience,
                                    JsonNode protocolData, long expectedRevision) {
        return db.tx(connection -> {
            DestinationRow existing = destination(destinationRef);
            if (existing == null) {
                if (expectedRevision != 0) {
                    throw new ChannelContractException(ChannelErrorCode.REQUEST_NOT_FOUND,
                        "destination does not exist; register it with expectedRevision=0");
                }
                upsertDestination(new DestinationRow(destinationRef, tenantRef, workspaceRef, entryRef,
                    accountBindingEpoch, null, null, null, audience, 1L, protocolData));
                return 1L;
            }
            if (expectedRevision != existing.destinationRevision()) {
                throw new ChannelContractException(ChannelErrorCode.BINDING_CONFLICT,
                    "destination revision does not match; re-read before updating");
            }
            long next = existing.destinationRevision() + 1;
            upsertDestination(new DestinationRow(destinationRef, tenantRef, workspaceRef, entryRef,
                accountBindingEpoch, existing.callerRef(), existing.identitySubjectKind(),
                existing.identitySubjectKey(), audience, next, protocolData));
            return next;
        });
    }

    public DestinationRow destination(String destinationRef) {
        return db.queryOne("SELECT * FROM l2_destinations WHERE destination_ref=?", rs -> new DestinationRow(
            Db.str(rs, "destination_ref"), Db.str(rs, "tenant_ref"), Db.str(rs, "workspace_ref"),
            Db.str(rs, "entry_ref"), rs.getLong("account_binding_epoch"), Db.str(rs, "caller_ref"),
            Db.str(rs, "identity_subject_kind"), Db.str(rs, "identity_subject_key"),
            Db.str(rs, "audience"), rs.getLong("destination_revision"),
            Json.read(Db.str(rs, "protocol_data"))), destinationRef);
    }

    /** 受众或身份绑定变化使旧版本失效；旧积压不转发给新身份（INV-13）。 */
    public long bumpDestinationRevision(String destinationRef, String audience, JsonNode protocolData) {
        return db.tx(connection -> {
            DestinationRow existing = destination(destinationRef);
            if (existing == null) {
                return 0L;
            }
            long next = existing.destinationRevision() + 1;
            upsertDestination(new DestinationRow(existing.destinationRef(), existing.tenantRef(),
                existing.workspaceRef(), existing.entryRef(), existing.accountBindingEpoch(),
                existing.callerRef(), existing.identitySubjectKind(), existing.identitySubjectKey(),
                audience == null ? existing.audience() : audience, next,
                protocolData == null ? existing.protocolData() : protocolData));
            return next;
        });
    }

    // ---- admissions ------------------------------------------------------------------

    public record AdmissionRow(String admissionRef, String entryRef, String callerRef, String scopeRef,
                               String replyTargetRef, String externalAccountKey, Instant expiresAt,
                               String identitySubjectKind, String identitySubjectKey,
                               long identityBindingRevision, long identityAccountBindingEpoch) {
    }

    public void saveAdmission(AdmissionRow row) {
        db.update("""
            INSERT INTO l2_admissions(admission_ref, entry_ref, caller_ref, scope_ref, reply_target_ref,
              external_account_key, expires_at, identity_subject_kind, identity_subject_key,
              identity_binding_revision, identity_account_binding_epoch, created_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            row.admissionRef(), row.entryRef(), row.callerRef(), row.scopeRef(), row.replyTargetRef(),
            row.externalAccountKey(), row.expiresAt().toString(), row.identitySubjectKind(),
            row.identitySubjectKey(), row.identityBindingRevision(), row.identityAccountBindingEpoch(),
            clock.instant().toString());
    }

    public AdmissionRow admission(String admissionRef) {
        return db.queryOne("SELECT * FROM l2_admissions WHERE admission_ref=?", rs -> new AdmissionRow(
            Db.str(rs, "admission_ref"), Db.str(rs, "entry_ref"), Db.str(rs, "caller_ref"),
            Db.str(rs, "scope_ref"), Db.str(rs, "reply_target_ref"), Db.str(rs, "external_account_key"),
            Instant.parse(Db.str(rs, "expires_at")), Db.str(rs, "identity_subject_kind"),
            Db.str(rs, "identity_subject_key"), rs.getLong("identity_binding_revision"),
            rs.getLong("identity_account_binding_epoch")), admissionRef);
    }

    public void purgeExpiredAdmissions(Instant now) {
        db.update("DELETE FROM l2_admissions WHERE expires_at<?", now.toString());
    }

    // ---- files -----------------------------------------------------------------------

    public record FileRow(String fileRef, String tenantRef, String workspaceRef, String targetRef,
                          String backendFileId, FileMetadata metadata, String state) {
    }

    /** 同一后端文件在同一目标内保持稳定 fileRef；新内容产生新 fileRef（INV-46）。 */
    public String ensureFile(String tenantRef, String workspaceRef, String targetRef, String backendFileId,
                             FileMetadata metadata) {
        return db.tx(connection -> {
            String existing = db.queryOne("""
                SELECT file_ref FROM l2_files WHERE target_ref IS ? AND backend_file_id=?
                """, rs -> Db.str(rs, "file_ref"), targetRef, backendFileId);
            if (existing != null) {
                return existing;
            }
            String fileRef = Ids.next("file");
            db.update("""
                INSERT INTO l2_files(file_ref, tenant_ref, workspace_ref, target_ref, backend_file_id,
                  metadata, state, created_at) VALUES(?,?,?,?,?,?,?,?)
                """, fileRef, tenantRef, workspaceRef, targetRef, backendFileId,
                Json.write(com.netlivecowork.channel.contract.json.ContentJson.encode(metadata)),
                "AVAILABLE", clock.instant().toString());
            return fileRef;
        });
    }

    public FileRow file(String fileRef) {
        return db.queryOne("SELECT * FROM l2_files WHERE file_ref=?", rs -> new FileRow(
            Db.str(rs, "file_ref"), Db.str(rs, "tenant_ref"), Db.str(rs, "workspace_ref"),
            Db.str(rs, "target_ref"), Db.str(rs, "backend_file_id"),
            com.netlivecowork.channel.contract.json.ContentJson.decodeMetadata(
                Json.read(Db.str(rs, "metadata"))),
            Db.str(rs, "state")), fileRef);
    }
}
