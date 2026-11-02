package com.netlivecowork.channel.l3;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.netlivecowork.channel.contract.FileMetadata;
import com.netlivecowork.channel.contract.TargetSummary;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.infra.Db;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * 目标与服务的可信登记（l3-routing-and-recovery.md §3）。L2 只保存 targetRef；服务地址、设备路径
 * 与会话映射只在这里。资源未可靠确认时不虚构可绑定目标（INV-03、11、51）。
 */
public final class BackendRegistry {

    private final Db db;
    private final Clock clock;

    public BackendRegistry(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
        db.ddl(
            """
            CREATE TABLE IF NOT EXISTS backend_services(
              service_ref TEXT PRIMARY KEY,
              tenant_ref TEXT NOT NULL,
              workspace_ref TEXT NOT NULL,
              label TEXT NOT NULL,
              base_url TEXT NOT NULL,
              access_type TEXT NOT NULL,
              credential_ref TEXT,
              enabled INTEGER NOT NULL,
              updated_at TEXT NOT NULL)
            """,
            """
            CREATE TABLE IF NOT EXISTS targets(
              target_ref TEXT PRIMARY KEY,
              tenant_ref TEXT NOT NULL,
              workspace_ref TEXT NOT NULL,
              owner_principal_ref TEXT,
              service_ref TEXT NOT NULL,
              session_id TEXT NOT NULL,
              resource_epoch INTEGER NOT NULL,
              label TEXT NOT NULL,
              agent_ref TEXT,
              capabilities TEXT NOT NULL,
              availability TEXT NOT NULL,
              binding_revision INTEGER NOT NULL,
              enabled INTEGER NOT NULL,
              updated_at TEXT NOT NULL,
              UNIQUE(service_ref, session_id, resource_epoch))
            """,
            """
            CREATE TABLE IF NOT EXISTS files(
              file_ref TEXT PRIMARY KEY,
              target_ref TEXT NOT NULL,
              service_ref TEXT NOT NULL,
              session_id TEXT NOT NULL,
              backend_file_id TEXT NOT NULL,
              name TEXT NOT NULL,
              media_type TEXT NOT NULL,
              size_bytes INTEGER NOT NULL,
              created_at TEXT NOT NULL)
            """);
        migrateDeviceRef();
    }

    private void migrateDeviceRef() {
        if (db.queryOne("SELECT name FROM pragma_table_info('backend_services') WHERE name='device_ref'", rs->rs.getString(1))==null)
            db.ddl("ALTER TABLE backend_services ADD COLUMN device_ref TEXT");
    }

    java.util.List<BackendService> allDeviceServices(String deviceRef) {
        return db.query("SELECT * FROM backend_services WHERE device_ref=? AND access_type='CONNECTOR'",
            BackendRegistry::mapService,deviceRef);
    }
    public java.util.List<BackendService> deviceServices(String deviceRef) {
        return db.query("SELECT * FROM backend_services WHERE device_ref=? AND enabled=1 AND access_type='CONNECTOR'",
            BackendRegistry::mapService, deviceRef);
    }

    // ---- services ---------------------------------------------------------------------

    public void upsertService(BackendService service) {
        migrateDeviceRef();
        db.update("""
            INSERT INTO backend_services(service_ref, tenant_ref, workspace_ref, label, base_url, access_type,
                                         credential_ref, enabled, updated_at, device_ref)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(service_ref) DO UPDATE SET tenant_ref=excluded.tenant_ref,
              workspace_ref=excluded.workspace_ref, label=excluded.label, base_url=excluded.base_url,
              access_type=excluded.access_type, credential_ref=excluded.credential_ref,
              enabled=excluded.enabled, updated_at=excluded.updated_at, device_ref=excluded.device_ref
            """,
            service.serviceRef(), service.tenantRef(), service.workspaceRef(), service.label(),
            service.baseUrl()==null?"":service.baseUrl(), service.accessType().name(), service.credentialRef(), service.enabled(),
            clock.instant(), service.deviceRef());
    }

    public BackendService service(String serviceRef) {
        migrateDeviceRef();
        if (serviceRef == null || serviceRef.isBlank()) {
            return null;
        }
        return db.queryOne("SELECT * FROM backend_services WHERE service_ref=?",
            BackendRegistry::mapService, serviceRef);
    }

    public List<BackendService> services(String tenant, String workspace) {
        return db.query("SELECT * FROM backend_services WHERE tenant_ref=? AND workspace_ref=? AND enabled=1 ORDER BY service_ref",
            BackendRegistry::mapService,tenant,workspace);
    }

    // ---- targets ----------------------------------------------------------------------

    public void upsertTarget(TargetRegistration target) {
        db.update("""
            INSERT INTO targets(target_ref, tenant_ref, workspace_ref, owner_principal_ref, service_ref,
                                session_id, resource_epoch, label, agent_ref, capabilities, availability,
                                binding_revision, enabled, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(target_ref) DO UPDATE SET tenant_ref=excluded.tenant_ref,
              workspace_ref=excluded.workspace_ref, owner_principal_ref=excluded.owner_principal_ref,
              service_ref=excluded.service_ref, session_id=excluded.session_id,
              resource_epoch=excluded.resource_epoch, label=excluded.label, agent_ref=excluded.agent_ref,
              capabilities=excluded.capabilities, availability=excluded.availability,
              binding_revision=excluded.binding_revision, enabled=excluded.enabled,
              updated_at=excluded.updated_at
            """,
            target.targetRef(), target.tenantRef(), target.workspaceRef(), target.ownerPrincipalRef(),
            target.serviceRef(), target.sessionId(), target.resourceEpoch(), target.label(),
            target.agentRef(), encodeStrings(target.capabilities()),
            target.availability().name(), target.bindingRevision(), target.enabled(), clock.instant());
    }

    public TargetRegistration target(String targetRef) {
        if (targetRef == null || targetRef.isBlank()) {
            return null;
        }
        return db.queryOne("SELECT * FROM targets WHERE target_ref=?", BackendRegistry::mapTarget, targetRef);
    }

    /** 同一后端会话可能有多个资源代次；取最新代次，不以历史代次回答新事件。 */
    public TargetRegistration targetBySession(String serviceRef, String sessionId) {
        if (serviceRef == null || sessionId == null) {
            return null;
        }
        return db.queryOne(
            "SELECT * FROM targets WHERE service_ref=? AND session_id=? ORDER BY resource_epoch DESC LIMIT 1",
            BackendRegistry::mapTarget, serviceRef, sessionId);
    }

    /**
     * 可信资源域内可展示的目标。共享目标不由发言人身份过滤：A 分享的目标在 B 发言时仍指原资源
     * （INV-02、21），受众披露规则属 L2。归属事实经 BackendAccessPort.targetOwnerships 按
     * 后端 facts 现场核验取得，静态登记不产生可信归属。
     */
    public List<TargetSummary> visibleTargets(String tenantRef, String workspaceRef, String principalRef,
                                              String agentRef) {
        List<TargetRegistration> rows = db.query("""
            SELECT * FROM targets
            WHERE tenant_ref=? AND workspace_ref=? AND enabled=1 AND (? IS NULL OR agent_ref=?)
            ORDER BY label, target_ref
            """, BackendRegistry::mapTarget, tenantRef, workspaceRef, agentRef, agentRef);
        List<TargetSummary> summaries = new ArrayList<>(rows.size());
        for (TargetRegistration row : rows) {
            summaries.add(new TargetSummary(row.targetRef(), row.agentRef(), row.label(), row.capabilities(),
                row.availability()));
        }
        return summaries;
    }

    // ---- files ------------------------------------------------------------------------

    /** 登记 L2 签发的 fileRef 到不透明后端文件关联（INV-42）；字节仍由后端持有。 */
    public void registerFile(String fileRef, String targetRef, String backendFileId, FileMetadata metadata) {
        TargetRegistration target = target(targetRef);
        if (target == null) {
            throw new IllegalArgumentException("file registration requires a registered target: " + targetRef);
        }
        if (fileRef == null || fileRef.isBlank() || backendFileId == null || backendFileId.isBlank()
            || metadata == null) {
            throw new IllegalArgumentException("file registration requires fileRef, backendFileId and metadata");
        }
        db.update("""
            INSERT INTO files(file_ref, target_ref, service_ref, session_id, backend_file_id, name, media_type,
                              size_bytes, created_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            ON CONFLICT(file_ref) DO UPDATE SET target_ref=excluded.target_ref,
              service_ref=excluded.service_ref, session_id=excluded.session_id,
              backend_file_id=excluded.backend_file_id, name=excluded.name,
              media_type=excluded.media_type, size_bytes=excluded.size_bytes
            """,
            fileRef, target.targetRef(), target.serviceRef(), target.sessionId(), backendFileId,
            metadata.name(), metadata.mediaType(), metadata.sizeBytes(), clock.instant());
    }

    FileLink file(String fileRef) {
        if (fileRef == null || fileRef.isBlank()) {
            return null;
        }
        return db.queryOne("SELECT * FROM files WHERE file_ref=?", rs -> new FileLink(
            Db.str(rs, "file_ref"), Db.str(rs, "target_ref"), Db.str(rs, "service_ref"),
            Db.str(rs, "session_id"), Db.str(rs, "backend_file_id"),
            new FileMetadata(Db.str(rs, "name"), Db.str(rs, "media_type"), rs.getLong("size_bytes"))), fileRef);
    }

    /** fileRef → 实际服务/会话/后端文件版本的不透明关联。 */
    record FileLink(String fileRef, String targetRef, String serviceRef, String sessionId,
                    String backendFileId, FileMetadata metadata) {
    }

    // ---- mapping ----------------------------------------------------------------------

    private static BackendService mapService(ResultSet rs) throws SQLException {
        return new BackendService(Db.str(rs, "service_ref"), Db.str(rs, "tenant_ref"),
            Db.str(rs, "workspace_ref"), Db.str(rs, "label"), Db.str(rs, "base_url"),
            AccessType.valueOf(Db.str(rs, "access_type")), Db.str(rs, "credential_ref"),
            Db.bool(rs, "enabled"), Db.str(rs,"device_ref"));
    }

    private static TargetRegistration mapTarget(ResultSet rs) throws SQLException {
        return new TargetRegistration(Db.str(rs, "target_ref"), Db.str(rs, "tenant_ref"),
            Db.str(rs, "workspace_ref"), Db.str(rs, "owner_principal_ref"), Db.str(rs, "service_ref"),
            Db.str(rs, "session_id"), rs.getLong("resource_epoch"), Db.str(rs, "label"),
            Db.str(rs, "agent_ref"), readStrings(Db.str(rs, "capabilities")),
            TargetSummary.Availability.valueOf(Db.str(rs, "availability")), rs.getLong("binding_revision"),
            Db.bool(rs, "enabled"));
    }

    private static String encodeStrings(List<String> values) {
        ArrayNode array = Json.arr();
        values.forEach(array::add);
        return Json.write(array);
    }

    private static List<String> readStrings(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (var item : Json.read(raw)) {
            values.add(item.asText());
        }
        return values;
    }
}
