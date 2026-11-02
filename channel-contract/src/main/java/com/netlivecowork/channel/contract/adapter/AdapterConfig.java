package com.netlivecowork.channel.contract.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 离线校验用配置：不含明文凭证，只声明需要的凭证用途（data-contracts.md §4.1）。 */
public record AdapterConfig(int configVersion, JsonNode settings, List<String> credentialNames) {
    public AdapterConfig {
        settings = settings == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : settings;
        credentialNames = credentialNames == null ? List.of() : List.copyOf(credentialNames);
    }
}
