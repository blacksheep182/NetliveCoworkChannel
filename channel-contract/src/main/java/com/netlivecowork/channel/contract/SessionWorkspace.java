package com.netlivecowork.channel.contract;
/** Backend-confirmed project grouping, never the trusted deployment workspace. */
public record SessionWorkspace(String sessionWorkspaceRef, String label, boolean canCreate) {
    public SessionWorkspace(String ref,String label) { this(ref,label,false); }
    public SessionWorkspace {
        Producer.requireText(sessionWorkspaceRef, "sessionWorkspaceRef");
        Producer.requireText(label, "workspace label");
    }
}
