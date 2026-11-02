package com.netlivecowork.channel.contract;

import java.util.Set;

/**
 * 会话级回传登记请求：稳定登记键、逻辑资源、事件范围与获准接收端（INV-65）。
 * 平台目的地不发送给后端。
 */
public record SessionWatchRequest(String requestKey, String tenantRef, String workspaceRef, String targetRef,
                                  Set<EventType> eventTypes, String sinkRef, String resumeCursor) {
    public SessionWatchRequest {
        Producer.requireText(requestKey, "watch.requestKey");
        Producer.requireText(tenantRef, "watch.tenantRef");
        Producer.requireText(workspaceRef, "watch.workspaceRef");
        Producer.requireText(targetRef, "watch.targetRef");
        if (eventTypes == null || eventTypes.isEmpty()) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "watch requires eventTypes");
        }
        eventTypes = Set.copyOf(eventTypes);
        Producer.requireText(sinkRef, "watch.sinkRef");
        resumeCursor = resumeCursor == null || resumeCursor.isBlank() ? null : resumeCursor;
    }
}
