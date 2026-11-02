package com.netlivecowork.channel.contract.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * 平台接入配置（L2 管理）。凭证只保存用途名到 secretRef 的映射，明文进凭据设施（INV-52）。
 */
public record EntryConfig(String tenantRef, String workspaceRef, String entryRef, String name, String adapterType,
                          int configVersion, long revision, long accountBindingEpoch, boolean enabled,
                          JsonNode settings, Map<String, String> credentials, ConnectionPolicy connectionPolicy,
                          String channelPolicyRef) {

    public EntryConfig {
        if (entryRef == null || entryRef.isBlank()) {
            throw new IllegalArgumentException("entryRef is required");
        }
        if (adapterType == null || adapterType.isBlank()) {
            throw new IllegalArgumentException("adapterType is required");
        }
        name = name == null || name.isBlank() ? entryRef : name;
        settings = settings == null
            ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : settings;
        credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
        connectionPolicy = connectionPolicy == null ? ConnectionPolicy.defaults() : connectionPolicy;
        channelPolicyRef = channelPolicyRef == null || channelPolicyRef.isBlank() ? "default" : channelPolicyRef;
    }

    public AdapterInstanceConfig instanceConfig(String expectedExternalAccountKey) {
        return new AdapterInstanceConfig(tenantRef, workspaceRef, entryRef, configVersion, revision,
            accountBindingEpoch, expectedExternalAccountKey, settings, java.util.List.copyOf(credentials.keySet()));
    }
}
