package com.netlivecowork.channel.contract.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.netlivecowork.channel.contract.ProtocolStateEntry;
import java.util.concurrent.CompletionStage;

/**
 * 协议状态存储：自动限定实例/代次/Adapter 版本命名空间，只存游标等协议状态，
 * 不存会话选择或授权结论（l1-adapter.md §10）。
 */
public interface ProtocolStateStore {

    CompletionStage<ProtocolStateEntry> get(String key);

    CompletionStage<Long> put(String key, Long expectedRevision, JsonNode value);

    CompletionStage<Void> remove(String key, long expectedRevision);
}
