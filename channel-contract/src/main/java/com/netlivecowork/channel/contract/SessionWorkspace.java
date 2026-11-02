package com.netlivecowork.channel.contract;
/** Backend-confirmed project grouping, never the trusted deployment workspace. */
public record SessionWorkspace(String sessionWorkspaceRef, String label) {
    public SessionWorkspace {
        Producer.requireText(sessionWorkspaceRef, "sessionWorkspaceRef");
        Producer.requireText(label, "workspace label");
    }
}
