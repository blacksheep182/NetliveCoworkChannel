package com.netlivecowork.channel.contract;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Adapter 解析出的平台私有定位信息。协议数据只在本实例内保存，不进入公共业务包（INV-09）。
 */
public record ResolvedDestination(String destinationRef, long revision, JsonNode protocolData) {
    public ResolvedDestination {
        Producer.requireText(destinationRef, "destinationRef");
    }
}
