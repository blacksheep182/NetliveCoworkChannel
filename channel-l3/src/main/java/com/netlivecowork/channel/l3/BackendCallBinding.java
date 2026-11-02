package com.netlivecowork.channel.l3;

/** Actual HTTP request binding supplied to the registered backend authentication adapter. */
public record BackendCallBinding(String backendId, String clientId, String tenantId, String workspaceId,
                                 String sessionId, long resourceEpoch, String action, String method,
                                 String path, String bodySha256, String requestKey) {}
