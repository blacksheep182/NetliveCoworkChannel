package com.netlivecowork.channel.contract.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;

/**
 * Adapter 自描述：类型、版本、配置模式与能力。configurationSchema 随 Adapter 打包，不联网下载。
 */
public record AdapterDescriptor(String type, String displayName, String implementationVersion,
                                List<String> contractVersions, List<Integer> configVersions,
                                JsonNode configurationSchema, JsonNode configurationUi,
                                AdapterCapabilities capabilities, Set<LifecycleMode> lifecycleModes) {

    public AdapterDescriptor {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("adapter type is required");
        }
        displayName = displayName == null || displayName.isBlank() ? type : displayName;
        implementationVersion = implementationVersion == null || implementationVersion.isBlank()
            ? "0.0.0" : implementationVersion;
        contractVersions = contractVersions == null ? List.of("1") : List.copyOf(contractVersions);
        configVersions = configVersions == null ? List.of(1) : List.copyOf(configVersions);
        if (capabilities == null) {
            throw new IllegalArgumentException("capabilities is required");
        }
        lifecycleModes = lifecycleModes == null || lifecycleModes.isEmpty()
            ? Set.of(capabilities.lifecycle()) : Set.copyOf(lifecycleModes);
        if (!lifecycleModes.contains(capabilities.lifecycle())) {
            throw new IllegalArgumentException("capabilities.lifecycle must be one of lifecycleModes");
        }
    }
}
