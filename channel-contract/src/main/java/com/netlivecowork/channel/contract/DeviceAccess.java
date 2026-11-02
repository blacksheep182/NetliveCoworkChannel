package com.netlivecowork.channel.contract;

import java.time.Instant;

/** Management-only device enrollment. These records are never Channel business packets. */
public final class DeviceAccess {
    private DeviceAccess() {}
    public record Authorization(String tenantRef, String workspaceRef, String requestId,
                                String serviceRef, String deviceRef) {}
    public record Ticket(String deviceRef, String ticket, Instant expiresAt) {}
}
