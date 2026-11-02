package com.netlivecowork.channel.app;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 部署配置。接入、服务、目标与会话的回传登记都来自受信配置，不接受客户端自报归属（INV-03）。
 * 凭证只保存引用或文件名，明文不进配置文件（INV-52）。
 */
@ConfigurationProperties(prefix = "channel")
public record ChannelProperties(String adminToken, String dataFile, String tenantRef, String workspaceRef,
                                boolean autoProvisionIdentities, String masterKeyId, L3 l3,
                                List<Service> services, List<Target> targets, List<Sink> sinks,
                                List<Entry> entries) {

    public ChannelProperties {
        dataFile = blankTo(dataFile, "./data/channel.db");
        tenantRef = blankTo(tenantRef, "tenant-local");
        workspaceRef = blankTo(workspaceRef, "workspace-local");
        masterKeyId = blankTo(masterKeyId, "local-key-1");
        l3 = l3 == null ? L3.defaults() : l3;
        services = services == null ? List.of() : List.copyOf(services);
        targets = targets == null ? List.of() : List.copyOf(targets);
        sinks = sinks == null ? List.of() : List.copyOf(sinks);
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** L3 运行参数。sink 是后端主动回传的接收端登记；token 由部署独立提供。 */
    public record L3(String sinkRef, String sinkToken, boolean filesSupported, boolean sessionWatchSupported,
                     long httpTimeoutMs, int forwardMaxAttempts, long forwardInitialBackoffMs,
                     long forwardMaxBackoffMs, int forwardLeaseSeconds) {
        public L3 {
            sinkRef = blankTo(sinkRef, "l3-sink-1");
            httpTimeoutMs = httpTimeoutMs <= 0 ? 10_000 : httpTimeoutMs;
            forwardMaxAttempts = forwardMaxAttempts <= 0 ? 8 : forwardMaxAttempts;
            forwardInitialBackoffMs = forwardInitialBackoffMs <= 0 ? 2_000 : forwardInitialBackoffMs;
            forwardMaxBackoffMs = forwardMaxBackoffMs <= 0 ? 300_000 : forwardMaxBackoffMs;
            forwardLeaseSeconds = forwardLeaseSeconds <= 0 ? 60 : forwardLeaseSeconds;
        }

        public static L3 defaults() {
            return new L3("l3-sink-1", null, false, false, 10_000, 8, 2_000, 300_000, 60);
        }
    }

    /** 后端服务登记：稳定服务身份与访问类型，地址只存在于 L3（INV-11）。 */
    public record Service(String serviceRef, String label, String baseUrl, String accessType,
                          String credentialRef, boolean enabled, String backendId, String clientId,
                          String backendTenantId, String backendWorkspaceId, String adapterId,
                          List<Principal> principals, String deviceRef) {
        @ConstructorBinding
        public Service {
            label = blankTo(label, serviceRef);
            accessType = blankTo(accessType, "LOCAL");
            principals = principals == null ? List.of() : List.copyOf(principals);
        }

        public Service(String serviceRef,String label,String baseUrl,String accessType,String credentialRef,
                       boolean enabled,String backendId,String clientId,String backendTenantId,
                       String backendWorkspaceId,String adapterId,List<Principal> principals) {
            this(serviceRef,label,baseUrl,accessType,credentialRef,enabled,backendId,clientId,backendTenantId,
                backendWorkspaceId,adapterId,principals,null);
        }
        public Service(String serviceRef, String label, String baseUrl, String accessType,
                       String credentialRef, boolean enabled) {
            this(serviceRef, label, baseUrl, accessType, credentialRef, enabled,
                null, null, null, null, null, List.of());
        }
    }

    /** 受控的 W3 subject 到内部主体映射；凭据只保存 env:/file: 引用。 */
    public record Principal(String principalRef, String subjectIssuer, String subject, String sessionProofRef) {
    }

    /**
     * 逻辑目标登记：targetRef 固定代表确定的服务与会话资源身份；resourceEpoch 区分数据重建与编号复用
     * （l3-routing-and-recovery.md §4）。
     */
    public record Target(String targetRef, String serviceRef, String sessionId, long resourceEpoch, String label,
                         String agentRef, List<String> capabilities, String availability,
                         String ownerPrincipalRef, long bindingRevision, boolean enabled) {
        public Target {
            label = blankTo(label, targetRef);
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            availability = blankTo(availability, "UNKNOWN");
            ownerPrincipalRef = blankTo(ownerPrincipalRef, "system");
            bindingRevision = bindingRevision <= 0 ? 1 : bindingRevision;
            resourceEpoch = resourceEpoch <= 0 ? 1 : resourceEpoch;
        }
    }

    /** 事件接收端登记：后端按 sinkRef 选择回传地址，配置变化不能把积压转投其他身份（INV-64）。 */
    public record Sink(String sinkRef, String serviceRef, String token, String requestKey,
                       long expectedRevision, String receiverId, String callbackUrl,
                       String callbackAuthBindingRef) {
        @ConstructorBinding
        public Sink {
        }
        public Sink(String sinkRef, String serviceRef, String token) {
            this(sinkRef, serviceRef, token, null, 0, null, null, null);
        }
    }

    /** 平台接入配置。settings 由 Adapter 自己解释；credentials 是用途名到 secretRef 的映射。 */
    public record Entry(String entryRef, String name, String adapterType, int configVersion,
                        long accountBindingEpoch, boolean enabled, String expectedExternalAccountKey,
                        Map<String, Object> settings, Map<String, String> credentials,
                        Map<String, Object> connectionPolicy, String channelPolicyRef) {
        public Entry {
            name = blankTo(name, entryRef);
            configVersion = configVersion <= 0 ? 1 : configVersion;
            accountBindingEpoch = accountBindingEpoch <= 0 ? 1 : accountBindingEpoch;
            settings = settings == null ? Map.of() : Map.copyOf(settings);
            credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
            channelPolicyRef = blankTo(channelPolicyRef, "default");
        }
    }
}
