package com.netlivecowork.channel.contract.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 实例配置：账号代次与预期平台账号，首次授权未完成时允许为空但不能处理业务。 */
public record AdapterInstanceConfig(String tenantRef, String workspaceRef, String entryRef, int configVersion,
                                    long configRevision, long accountBindingEpoch,
                                    String expectedExternalAccountKey, JsonNode settings,
                                    List<String> credentialNames) {

    public AdapterInstanceConfig {
        if (entryRef == null || entryRef.isBlank()) {
            throw new IllegalArgumentException("entryRef is required");
        }
        settings = settings == null
            ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : settings;
        credentialNames = credentialNames == null ? List.of() : List.copyOf(credentialNames);
        expectedExternalAccountKey = expectedExternalAccountKey == null || expectedExternalAccountKey.isBlank()
            ? null : expectedExternalAccountKey;
    }

    public AdapterConfig config() {
        return new AdapterConfig(configVersion, settings, credentialNames);
    }
}
