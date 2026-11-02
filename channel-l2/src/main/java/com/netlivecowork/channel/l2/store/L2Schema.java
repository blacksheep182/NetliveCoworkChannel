package com.netlivecowork.channel.l2.store;

import com.netlivecowork.channel.infra.Db;

/**
 * L2 持久化结构（l2-application.md §4）。表名与文档建议名一致；跨层不共享事务，
 * 网络调用一律在事务外（INV-16、INV-20）。
 */
public final class L2Schema {

    private L2Schema() {
    }

    private static final String[] DDL = {
        """
        CREATE TABLE IF NOT EXISTS l2_entries(
          tenant_ref TEXT NOT NULL, workspace_ref TEXT NOT NULL, entry_ref TEXT NOT NULL,
          name TEXT NOT NULL, adapter_type TEXT NOT NULL, config_version INTEGER NOT NULL,
          revision INTEGER NOT NULL, account_binding_epoch INTEGER NOT NULL,
          enabled INTEGER NOT NULL, settings TEXT NOT NULL, credentials TEXT NOT NULL,
          connection_policy TEXT NOT NULL, channel_policy_ref TEXT NOT NULL,
          expected_external_account_key TEXT, observed_external_account_key TEXT,
          created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
          PRIMARY KEY(tenant_ref, workspace_ref, entry_ref))
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_connection_runtime(
          entry_ref TEXT PRIMARY KEY, generation INTEGER NOT NULL,
          attempt_generation INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL,
          last_seen TEXT, retry_count INTEGER NOT NULL DEFAULT 0, next_retry_at TEXT,
          owner_lease TEXT, updated_at TEXT NOT NULL)
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_adapter_state(
          entry_ref TEXT NOT NULL, generation INTEGER NOT NULL, adapter_version TEXT NOT NULL,
          key TEXT NOT NULL, value TEXT NOT NULL, revision INTEGER NOT NULL, updated_at TEXT NOT NULL,
          PRIMARY KEY(entry_ref, generation, adapter_version, key))
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_secrets(
          secret_ref TEXT PRIMARY KEY, entry_ref TEXT NOT NULL, purpose TEXT NOT NULL,
          cipher TEXT NOT NULL, revision INTEGER NOT NULL, key_id TEXT NOT NULL, updated_at TEXT NOT NULL)
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_identity_bindings(
          entry_ref TEXT NOT NULL, account_binding_epoch INTEGER NOT NULL,
          external_subject_kind TEXT NOT NULL, external_subject_key TEXT NOT NULL,
          caller_ref TEXT NOT NULL, binding_revision INTEGER NOT NULL, created_at TEXT NOT NULL,
          PRIMARY KEY(entry_ref, account_binding_epoch, external_subject_kind, external_subject_key))
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_scopes(
          tenant_ref TEXT NOT NULL, workspace_ref TEXT NOT NULL, scope_ref TEXT NOT NULL,
          entry_ref TEXT NOT NULL, account_binding_epoch INTEGER NOT NULL, caller_ref TEXT,
          identity_subject_kind TEXT, identity_subject_key TEXT,
          namespace TEXT NOT NULL, external_key TEXT NOT NULL, audience TEXT NOT NULL,
          shared INTEGER NOT NULL, created_at TEXT NOT NULL,
          PRIMARY KEY(tenant_ref, workspace_ref, scope_ref))
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_scope_bindings(
          tenant_ref TEXT NOT NULL, workspace_ref TEXT NOT NULL, scope_ref TEXT NOT NULL,
          target_ref TEXT NOT NULL, revision INTEGER NOT NULL, updated_by TEXT NOT NULL,
          updated_at TEXT NOT NULL, PRIMARY KEY(tenant_ref, workspace_ref, scope_ref))
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_targets(
          target_ref TEXT PRIMARY KEY, tenant_ref TEXT NOT NULL, workspace_ref TEXT NOT NULL,
          agent_ref TEXT, label TEXT NOT NULL, capabilities TEXT NOT NULL, availability TEXT NOT NULL,
          resource_ref TEXT, binding_revision INTEGER NOT NULL, updated_at TEXT NOT NULL)
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_destinations(
          destination_ref TEXT PRIMARY KEY, tenant_ref TEXT NOT NULL, workspace_ref TEXT NOT NULL,
          entry_ref TEXT NOT NULL, account_binding_epoch INTEGER NOT NULL, caller_ref TEXT,
          identity_subject_kind TEXT, identity_subject_key TEXT,
          audience TEXT NOT NULL, destination_revision INTEGER NOT NULL, protocol_data TEXT NOT NULL,
          created_at TEXT NOT NULL, updated_at TEXT NOT NULL)
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_admissions(
          admission_ref TEXT PRIMARY KEY, entry_ref TEXT NOT NULL, caller_ref TEXT NOT NULL,
          identity_subject_kind TEXT, identity_subject_key TEXT,
          identity_binding_revision INTEGER NOT NULL DEFAULT 0,
          identity_account_binding_epoch INTEGER NOT NULL DEFAULT 0,
          scope_ref TEXT, reply_target_ref TEXT, external_account_key TEXT NOT NULL,
          expires_at TEXT NOT NULL, created_at TEXT NOT NULL)
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_inbox_operations(
          dedupe_key TEXT PRIMARY KEY, tenant_ref TEXT NOT NULL, workspace_ref TEXT NOT NULL,
          entry_ref TEXT NOT NULL, account_binding_epoch INTEGER NOT NULL, caller_ref TEXT NOT NULL,
          request_id TEXT NOT NULL, request_digest TEXT NOT NULL, packet_id TEXT NOT NULL,
          operation_ref TEXT NOT NULL UNIQUE, flow_id TEXT NOT NULL, command_type TEXT NOT NULL,
          command_json TEXT NOT NULL, status TEXT NOT NULL, response_json TEXT,
          identity_subject_kind TEXT, identity_subject_key TEXT,
          identity_binding_revision INTEGER NOT NULL DEFAULT 0,
          dispatch_attempts INTEGER NOT NULL DEFAULT 0, next_dispatch_at TEXT,
          created_at TEXT NOT NULL, updated_at TEXT NOT NULL)
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_reply_relations(
          relation_id TEXT PRIMARY KEY, flow_id TEXT NOT NULL, operation_ref TEXT NOT NULL,
          tenant_ref TEXT NOT NULL, workspace_ref TEXT NOT NULL, entry_ref TEXT NOT NULL,
          destination_ref TEXT NOT NULL, destination_revision INTEGER NOT NULL,
          authorized_by TEXT NOT NULL, scope_ref TEXT, shared INTEGER NOT NULL,
          source TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_tasks(
          task_ref TEXT PRIMARY KEY, tenant_ref TEXT NOT NULL, workspace_ref TEXT NOT NULL,
          target_ref TEXT NOT NULL, flow_id TEXT NOT NULL, operation_ref TEXT,
          backend_task_id TEXT, status TEXT NOT NULL, revision INTEGER NOT NULL,
          created_at TEXT NOT NULL, updated_at TEXT NOT NULL)
        """,
        "CREATE UNIQUE INDEX IF NOT EXISTS l2_tasks_backend ON l2_tasks(target_ref, backend_task_id)",
        """
        CREATE TABLE IF NOT EXISTS l2_messages(
          message_ref TEXT PRIMARY KEY, target_ref TEXT NOT NULL, flow_id TEXT NOT NULL,
          backend_message_id TEXT NOT NULL, task_ref TEXT, content TEXT NOT NULL, created_at TEXT NOT NULL,
          UNIQUE(target_ref, backend_message_id))
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_operation_receipts(
          packet_id TEXT PRIMARY KEY, operation_ref TEXT NOT NULL, command_packet_id TEXT NOT NULL,
          payload_digest TEXT NOT NULL, payload_json TEXT NOT NULL, applied INTEGER NOT NULL DEFAULT 0,
          received_at TEXT NOT NULL)
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_backend_events(
          resource_ref TEXT NOT NULL, source_event_id TEXT NOT NULL, backend_event_ref TEXT NOT NULL,
          payload_digest TEXT NOT NULL, event_json TEXT NOT NULL, packet_id TEXT, packet_json TEXT,
          state TEXT NOT NULL, last_error TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
          PRIMARY KEY(resource_ref, source_event_id))
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_subscriptions(
          subscription_ref TEXT PRIMARY KEY, tenant_ref TEXT NOT NULL, workspace_ref TEXT NOT NULL,
          subscriber_ref TEXT NOT NULL, resource_type TEXT NOT NULL, resource_ref TEXT NOT NULL,
          event_types TEXT NOT NULL, entry_ref TEXT NOT NULL, destination_ref TEXT NOT NULL,
          destination_revision INTEGER NOT NULL, status TEXT NOT NULL, generation INTEGER NOT NULL,
          revision INTEGER NOT NULL, cursor TEXT, start_boundary TEXT, watch_ref TEXT,
          created_at TEXT NOT NULL, updated_at TEXT NOT NULL)
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_outbox(
          delivery_id TEXT PRIMARY KEY, tenant_ref TEXT NOT NULL, workspace_ref TEXT NOT NULL,
          entry_ref TEXT NOT NULL, destination_ref TEXT NOT NULL, destination_revision INTEGER NOT NULL,
          packet_id TEXT NOT NULL, delivery_json TEXT NOT NULL, status TEXT NOT NULL,
          attempt_count INTEGER NOT NULL, next_attempt_at TEXT, lease_until TEXT, owner TEXT,
          receipt_ref TEXT, last_error TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
          UNIQUE(tenant_ref, workspace_ref, entry_ref, destination_ref, destination_revision, packet_id))
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_outbox_relations(
          delivery_id TEXT NOT NULL, relation_type TEXT NOT NULL, relation_ref TEXT NOT NULL,
          generation INTEGER NOT NULL, authorized_by TEXT NOT NULL, active INTEGER NOT NULL,
          PRIMARY KEY(delivery_id, relation_type, relation_ref))
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_interactions(
          interaction_ref TEXT PRIMARY KEY, tenant_ref TEXT NOT NULL, workspace_ref TEXT NOT NULL,
          target_ref TEXT NOT NULL, task_ref TEXT, flow_id TEXT NOT NULL,
          backend_interaction_id TEXT NOT NULL, prompt_json TEXT NOT NULL, revision INTEGER NOT NULL,
          status TEXT NOT NULL, expires_at TEXT, scope_ref TEXT, destination_ref TEXT,
          origin_packet_id TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
          UNIQUE(target_ref, backend_interaction_id))
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_interaction_actions(
          action_ref TEXT PRIMARY KEY, interaction_ref TEXT NOT NULL, revision INTEGER NOT NULL,
          response_kind TEXT NOT NULL, entry_ref TEXT NOT NULL, scope_ref TEXT, destination_ref TEXT,
          consumed INTEGER NOT NULL, expires_at TEXT, created_at TEXT NOT NULL)
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_interaction_responses(
          response_ref TEXT PRIMARY KEY, interaction_ref TEXT NOT NULL, operation_ref TEXT NOT NULL,
          responder_ref TEXT NOT NULL, prompt_revision INTEGER NOT NULL, response_json TEXT NOT NULL,
          status TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_files(
          file_ref TEXT PRIMARY KEY, tenant_ref TEXT NOT NULL, workspace_ref TEXT NOT NULL,
          target_ref TEXT, backend_file_id TEXT, metadata TEXT NOT NULL, state TEXT NOT NULL,
          created_at TEXT NOT NULL, UNIQUE(target_ref, backend_file_id))
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_backend_registrations(
          registration_ref TEXT PRIMARY KEY, kind TEXT NOT NULL, target_ref TEXT NOT NULL,
          local_ref TEXT NOT NULL, backend_ref TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0,
          payload_json TEXT, state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
          next_attempt_at TEXT, last_error TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
          UNIQUE(kind, local_ref))
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_grants(
          grant_ref TEXT PRIMARY KEY, tenant_ref TEXT NOT NULL, workspace_ref TEXT NOT NULL,
          grantee_principal_ref TEXT NOT NULL, target_ref TEXT NOT NULL, resource_epoch INTEGER NOT NULL,
          task_ref TEXT, terms_json TEXT NOT NULL, revision INTEGER NOT NULL, state TEXT NOT NULL,
          created_by TEXT NOT NULL, updated_by TEXT NOT NULL,
          created_at TEXT NOT NULL, updated_at TEXT NOT NULL, revoked_at TEXT)
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_grant_requests(
          dedupe_key TEXT PRIMARY KEY, request_id TEXT NOT NULL, operation TEXT NOT NULL,
          request_digest TEXT NOT NULL, change_ref TEXT NOT NULL, outcome TEXT NOT NULL,
          grant_ref TEXT NOT NULL, committed_at TEXT NOT NULL)
        """,
        """
        CREATE TABLE IF NOT EXISTS l2_inbound_dispositions(
          entry_ref TEXT NOT NULL, account_binding_epoch INTEGER NOT NULL, source_event_id TEXT NOT NULL,
          content_digest TEXT, code TEXT NOT NULL, state TEXT NOT NULL,
          recorded_at TEXT NOT NULL, retain_until TEXT NOT NULL,
          PRIMARY KEY(entry_ref, account_binding_epoch, source_event_id))
        """
    };

    public static void apply(Db db) {
        db.ddl(DDL);
        db.ddl("""
            CREATE TABLE IF NOT EXISTS l2_authorization_attempts(
              attempt_ref INTEGER PRIMARY KEY AUTOINCREMENT, operation_ref TEXT NOT NULL, started_at TEXT NOT NULL)
            """, """
            CREATE TABLE IF NOT EXISTS l2_grant_snapshots(
              request_key TEXT PRIMARY KEY, snapshot_json TEXT NOT NULL)
            """, """
            CREATE TABLE IF NOT EXISTS l2_grant_history(
              grant_ref TEXT NOT NULL, revision INTEGER NOT NULL, terms_json TEXT NOT NULL,
              PRIMARY KEY(grant_ref, revision))
            """, """
            CREATE TABLE IF NOT EXISTS l2_authorization_basis(
              operation_ref TEXT PRIMARY KEY, basis_json TEXT NOT NULL)
            """);
        ensureColumn(db, "l2_scope_bindings", "session_workspace_ref", "TEXT");
        ensureColumn(db, "l2_targets", "resource_epoch", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(db, "l2_targets", "authority_generation", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(db, "l2_targets", "evidence_valid_until", "TEXT");
        ensureColumn(db, "l2_targets", "evidence_revision", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(db, "l2_targets", "delegable_terms", "TEXT");
        ensureColumn(db, "l2_grants", "authority_generation", "INTEGER NOT NULL DEFAULT -1");
        // 旧库就地补列：身份隔离需要记录 scope/destination 的归属主体（INV-54、55）。
        ensureColumn(db, "l2_scopes", "caller_ref", "TEXT");
        ensureColumn(db, "l2_scopes", "identity_subject_kind", "TEXT");
        ensureColumn(db, "l2_scopes", "identity_subject_key", "TEXT");
        ensureColumn(db, "l2_destinations", "caller_ref", "TEXT");
        ensureColumn(db, "l2_destinations", "identity_subject_kind", "TEXT");
        ensureColumn(db, "l2_destinations", "identity_subject_key", "TEXT");
        ensureColumn(db, "l2_admissions", "identity_binding_revision", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(db, "l2_admissions", "identity_account_binding_epoch", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(db, "l2_operation_receipts", "applied", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(db, "l2_targets", "owner_principal_ref", "TEXT");
        ensureColumn(db, "l2_connection_runtime", "attempt_generation", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(db, "l2_admissions", "identity_subject_kind", "TEXT");
        ensureColumn(db, "l2_admissions", "identity_subject_key", "TEXT");
        ensureColumn(db, "l2_inbox_operations", "identity_subject_kind", "TEXT");
        ensureColumn(db, "l2_inbox_operations", "identity_subject_key", "TEXT");
        ensureColumn(db, "l2_inbox_operations", "identity_binding_revision",
            "INTEGER NOT NULL DEFAULT 0");
    }

    private static void ensureColumn(Db db, String table, String column, String type) {
        boolean present = db.query("PRAGMA table_info(" + table + ")", rs -> rs.getString("name"))
            .stream().anyMatch(name -> name != null && name.equalsIgnoreCase(column));
        if (!present) {
            db.ddl("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }
}
