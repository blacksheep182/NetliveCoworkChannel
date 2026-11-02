package com.netlivecowork.channel.contract;

import com.fasterxml.jackson.databind.JsonNode;

/** 实例限定的协议状态条目：只存游标等协议状态，不存会话选择或授权结论。 */
public record ProtocolStateEntry(long revision, JsonNode value) {
    public ProtocolStateEntry {
        if (value == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "state value is required");
        }
    }
}
