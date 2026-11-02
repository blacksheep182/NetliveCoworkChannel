package com.netlivecowork.channel.l2.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.ChannelError;
import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ProtocolStateEntry;
import com.netlivecowork.channel.contract.adapter.ConnectionPolicy;
import com.netlivecowork.channel.contract.adapter.EntryConfig;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.infra.Db;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 接入配置、连接代次、Adapter 协议状态与凭据记录（l2-application.md §4）。 */
public final class EntryRepository {

    private final Db db;
    private final Clock clock;

    public EntryRepository(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    // ---- entries ---------------------------------------------------------------------

    public void save(EntryConfig config, String expectedExternalAccountKey) {
        db.update("""
            INSERT INTO l2_entries(tenant_ref, workspace_ref, entry_ref, name, adapter_type, config_version,
              revision, account_binding_epoch, enabled, settings, credentials, connection_policy,
              channel_policy_ref, expected_external_account_key, observed_external_account_key,
              created_at, updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(tenant_ref, workspace_ref, entry_ref) DO UPDATE SET
              name=excluded.name, adapter_type=excluded.adapter_type, config_version=excluded.config_version,
              revision=excluded.revision, account_binding_epoch=excluded.account_binding_epoch,
              enabled=excluded.enabled, settings=excluded.settings, credentials=excluded.credentials,
              connection_policy=excluded.connection_policy, channel_policy_ref=excluded.channel_policy_ref,
              expected_external_account_key=excluded.expected_external_account_key,
              observed_external_account_key=COALESCE(l2_entries.observed_external_account_key,
                excluded.observed_external_account_key),
              updated_at=excluded.updated_at
            """,
            config.tenantRef(), config.workspaceRef(), config.entryRef(), config.name(), config.adapterType(),
            config.configVersion(), config.revision(), config.accountBindingEpoch(), config.enabled(),
            Json.write(config.settings()), Json.write(Json.mapper().valueToTree(config.credentials())),
            policy(config.connectionPolicy()), config.channelPolicyRef(), expectedExternalAccountKey,
            null, clock.instant().toString(), clock.instant().toString());
    }

    public EntryConfig find(String entryRef) {
        return db.queryOne("SELECT * FROM l2_entries WHERE entry_ref=?", this::map, entryRef);
    }

    public EntryConfig find(String tenantRef, String workspaceRef, String entryRef) {
        return db.queryOne("SELECT * FROM l2_entries WHERE tenant_ref=? AND workspace_ref=? AND entry_ref=?",
            this::map, tenantRef, workspaceRef, entryRef);
    }

    /**
     * 账号换绑：递增账号代次并提升配置版本。旧代次的 admission、排队工作与身份映射随之失效
     * （SM07 §9.1）。用 revision CAS 防止并发覆盖。
     */
    public long bumpAccountBindingEpoch(String entryRef, long expectedRevision) {
        return db.tx(connection -> {
            EntryConfig current = find(entryRef);
            if (current == null) {
                throw new ChannelContractException(ChannelErrorCode.REQUEST_NOT_FOUND, "unknown entry");
            }
            if (current.revision() != expectedRevision) {
                throw new ChannelContractException(ChannelErrorCode.BINDING_CONFLICT,
                    "entry revision does not match; re-read before rebinding the account");
            }
            long next = current.accountBindingEpoch() + 1;
            db.update("""
                UPDATE l2_entries SET account_binding_epoch=?, revision=revision+1, updated_at=?
                WHERE entry_ref=?
                """, next, clock.instant().toString(), entryRef);
            return next;
        });
    }

    public List<EntryConfig> all() {
        return db.query("SELECT * FROM l2_entries ORDER BY entry_ref", this::map);
    }

    public String expectedExternalAccountKey(String entryRef) {
        return db.queryOne("SELECT expected_external_account_key FROM l2_entries WHERE entry_ref=?",
            rs -> Db.str(rs, "expected_external_account_key"), entryRef);
    }

    /** 首次取得已验证账号时登记；此后实际账号不得静默改变（INV-12）。 */
    public void recordObservedAccount(String entryRef, String externalAccountKey) {
        db.update("UPDATE l2_entries SET observed_external_account_key=?, updated_at=? "
            + "WHERE entry_ref=? AND observed_external_account_key IS NULL",
            externalAccountKey, clock.instant().toString(), entryRef);
    }

    public String observedAccount(String entryRef) {
        return db.queryOne("SELECT observed_external_account_key FROM l2_entries WHERE entry_ref=?",
            rs -> Db.str(rs, "observed_external_account_key"), entryRef);
    }

    private EntryConfig map(java.sql.ResultSet rs) throws java.sql.SQLException {
        JsonNode settings = Json.read(Db.str(rs, "settings"));
        Map<String, String> credentials = new LinkedHashMap<>();
        JsonNode credentialsNode = Json.read(Db.str(rs, "credentials"));
        credentialsNode.fields().forEachRemaining(entry -> credentials.put(entry.getKey(), entry.getValue().asText()));
        return new EntryConfig(Db.str(rs, "tenant_ref"), Db.str(rs, "workspace_ref"), Db.str(rs, "entry_ref"),
            Db.str(rs, "name"), Db.str(rs, "adapter_type"), (int) rs.getLong("config_version"),
            rs.getLong("revision"), rs.getLong("account_binding_epoch"), Db.bool(rs, "enabled"), settings,
            credentials, policy(Db.str(rs, "connection_policy")), Db.str(rs, "channel_policy_ref"));
    }

    private static String policy(ConnectionPolicy policy) {
        ObjectNode node = Json.obj();
        node.put("connectTimeoutMs", policy.connectTimeoutMs());
        node.put("probeIntervalMs", policy.probeIntervalMs());
        node.put("probeTimeoutMs", policy.probeTimeoutMs());
        ObjectNode reconnect = node.putObject("reconnect");
        reconnect.put("enabled", policy.reconnect().enabled());
        reconnect.put("initialDelayMs", policy.reconnect().initialDelayMs());
        reconnect.put("maxDelayMs", policy.reconnect().maxDelayMs());
        if (policy.reconnect().maxAttempts() != null) {
            reconnect.put("maxAttempts", policy.reconnect().maxAttempts());
        }
        return Json.write(node);
    }

    private static ConnectionPolicy policy(String json) {
        JsonNode node = Json.read(json);
        JsonNode reconnect = node.path("reconnect");
        Integer maxAttempts = reconnect.hasNonNull("maxAttempts")
            ? reconnect.path("maxAttempts").asInt() : null;
        return new ConnectionPolicy(node.path("connectTimeoutMs").asLong(10_000),
            node.path("probeIntervalMs").asLong(30_000), node.path("probeTimeoutMs").asLong(5_000),
            new ConnectionPolicy.Reconnect(reconnect.path("enabled").asBoolean(true),
                reconnect.path("initialDelayMs").asLong(1_000), reconnect.path("maxDelayMs").asLong(60_000),
                maxAttempts));
    }

    // ---- connection runtime ----------------------------------------------------------

    public enum ConnectionState {
        DISABLED, STARTING, READY, CONNECTING, ONLINE, BACKOFF, AUTH_REQUIRED, BLOCKED, STOPPING
    }

    public record Runtime(String entryRef, long generation, long attemptGeneration, ConnectionState state,
                          Instant lastSeen, int retryCount, Instant nextRetryAt, String ownerLease) {

        public static Runtime initial(String entryRef) {
            return new Runtime(entryRef, 0, 0, ConnectionState.DISABLED, null, 0, null, null);
        }

        public Runtime with(long generation, long attemptGeneration, ConnectionState state, Instant lastSeen,
                            int retryCount, Instant nextRetryAt) {
            return new Runtime(entryRef, generation, attemptGeneration, state, lastSeen, retryCount,
                nextRetryAt, ownerLease);
        }
    }

    public Runtime runtime(String entryRef) {
        Runtime runtime = db.queryOne("SELECT * FROM l2_connection_runtime WHERE entry_ref=?", rs -> new Runtime(
            Db.str(rs, "entry_ref"), rs.getLong("generation"), rs.getLong("attempt_generation"),
            ConnectionState.valueOf(Db.str(rs, "state")), Db.instant(rs, "last_seen"),
            (int) rs.getLong("retry_count"), Db.instant(rs, "next_retry_at"), Db.str(rs, "owner_lease")),
            entryRef);
        return runtime == null ? Runtime.initial(entryRef) : runtime;
    }

    public void saveRuntime(Runtime runtime) {
        db.update("""
            INSERT INTO l2_connection_runtime(entry_ref, generation, attempt_generation, state, last_seen,
              retry_count, next_retry_at, owner_lease, updated_at) VALUES(?,?,?,?,?,?,?,?,?)
            ON CONFLICT(entry_ref) DO UPDATE SET generation=excluded.generation,
              attempt_generation=excluded.attempt_generation, state=excluded.state,
              last_seen=excluded.last_seen, retry_count=excluded.retry_count,
              next_retry_at=excluded.next_retry_at, owner_lease=excluded.owner_lease,
              updated_at=excluded.updated_at
            """,
            runtime.entryRef(), runtime.generation(), runtime.attemptGeneration(), runtime.state().name(),
            runtime.lastSeen() == null ? null : runtime.lastSeen().toString(), runtime.retryCount(),
            runtime.nextRetryAt() == null ? null : runtime.nextRetryAt().toString(), runtime.ownerLease(),
            clock.instant().toString());
    }

    /** 连接尝试代次独立且持久单调：实例替换后也不会与旧实例的尝试代次重号（INV-49）。 */
    public long bumpAttemptGeneration(String entryRef) {
        return db.tx(connection -> {
            Runtime runtime = runtime(entryRef);
            long next = runtime.attemptGeneration() + 1;
            saveRuntime(runtime.with(runtime.generation(), next, runtime.state(), runtime.lastSeen(),
                runtime.retryCount(), runtime.nextRetryAt()));
            return next;
        });
    }

    /** generation 单调递增并持久化；旧代次回调一律拒绝（INV-49）。 */
    public long bumpGeneration(String entryRef) {
        return db.tx(connection -> {
            Runtime runtime = runtime(entryRef);
            long next = runtime.generation() + 1;
            saveRuntime(runtime.with(next, runtime.attemptGeneration(), runtime.state(), runtime.lastSeen(),
                runtime.retryCount(), runtime.nextRetryAt()));
            return next;
        });
    }

    // ---- adapter protocol state ------------------------------------------------------

    public ProtocolStateEntry state(String entryRef, long generation, String adapterVersion, String key) {
        return db.queryOne("""
            SELECT revision, value FROM l2_adapter_state
            WHERE entry_ref=? AND generation=? AND adapter_version=? AND key=?
            """,
            rs -> new ProtocolStateEntry(rs.getLong("revision"), Json.read(Db.str(rs, "value"))),
            entryRef, generation, adapterVersion, key);
    }

    /** expectedRevision=null 表示仅允许首次写入；返回新版本。 */
    public long putState(String entryRef, long generation, String adapterVersion, String key,
                         Long expectedRevision, JsonNode value) {
        return db.tx(connection -> {
            ProtocolStateEntry existing = state(entryRef, generation, adapterVersion, key);
            if (existing == null) {
                if (expectedRevision != null) {
                    throw new ChannelContractException(ChannelErrorCode.INTERNAL_ERROR,
                        "protocol state does not exist: " + key);
                }
                db.update("""
                    INSERT INTO l2_adapter_state(entry_ref, generation, adapter_version, key, value,
                      revision, updated_at) VALUES(?,?,?,?,?,?,?)
                    """,
                    entryRef, generation, adapterVersion, key, Json.write(value), 1L,
                    clock.instant().toString());
                return 1L;
            }
            if (expectedRevision == null || expectedRevision != existing.revision()) {
                throw new ChannelContractException(ChannelErrorCode.INTERNAL_ERROR,
                    "protocol state revision conflict: " + key);
            }
            db.update("""
                UPDATE l2_adapter_state SET value=?, revision=revision+1, updated_at=?
                WHERE entry_ref=? AND generation=? AND adapter_version=? AND key=? AND revision=?
                """,
                Json.write(value), clock.instant().toString(), entryRef, generation, adapterVersion, key,
                existing.revision());
            return existing.revision() + 1;
        });
    }

    public void removeState(String entryRef, long generation, String adapterVersion, String key,
                            long expectedRevision) {
        int rows = db.update("""
            DELETE FROM l2_adapter_state
            WHERE entry_ref=? AND generation=? AND adapter_version=? AND key=? AND revision=?
            """,
            entryRef, generation, adapterVersion, key, expectedRevision);
        if (rows == 0) {
            throw new ChannelContractException(ChannelErrorCode.INTERNAL_ERROR,
                "protocol state revision conflict on remove: " + key);
        }
    }

    // ---- secrets ---------------------------------------------------------------------

    public record SecretRow(String secretRef, long revision, String cipher, String keyId) {
    }

    public SecretRow secret(String secretRef) {
        return db.queryOne("SELECT secret_ref, revision, cipher, key_id FROM l2_secrets WHERE secret_ref=?",
            rs -> new SecretRow(Db.str(rs, "secret_ref"), rs.getLong("revision"), Db.str(rs, "cipher"),
                Db.str(rs, "key_id")), secretRef);
    }

    public SecretRow secretByPurpose(String entryRef, String purpose) {
        return db.queryOne("""
            SELECT secret_ref, revision, cipher, key_id FROM l2_secrets
            WHERE entry_ref=? AND purpose=? ORDER BY revision DESC LIMIT 1
            """,
            rs -> new SecretRow(Db.str(rs, "secret_ref"), rs.getLong("revision"), Db.str(rs, "cipher"),
                Db.str(rs, "key_id")), entryRef, purpose);
    }

    public void saveSecret(String secretRef, String entryRef, String purpose, String cipher, long revision,
                           String keyId) {
        db.update("""
            INSERT INTO l2_secrets(secret_ref, entry_ref, purpose, cipher, revision, key_id, updated_at)
            VALUES(?,?,?,?,?,?,?)
            ON CONFLICT(secret_ref) DO UPDATE SET cipher=excluded.cipher, revision=excluded.revision,
              key_id=excluded.key_id, updated_at=excluded.updated_at
            """,
            secretRef, entryRef, purpose, cipher, revision, keyId, clock.instant().toString());
    }

    /** 清理旧代次的协议状态与身份映射，避免旧绑定被新账号沿用（INV-12）。 */
    public void purgeOtherGenerations(String entryRef, long generation) {
        db.update("DELETE FROM l2_adapter_state WHERE entry_ref=? AND generation<>?", entryRef, generation);
    }

    public List<EntryConfig> enabledEntries() {
        List<EntryConfig> entries = new ArrayList<>();
        for (EntryConfig config : all()) {
            if (config.enabled()) {
                entries.add(config);
            }
        }
        return entries;
    }

    /** 便于就绪检查的错误出口，不暴露内部细节。 */
    public static ChannelError unavailable(String message) {
        return ChannelError.of(ChannelErrorCode.UNAVAILABLE, message);
    }
}
