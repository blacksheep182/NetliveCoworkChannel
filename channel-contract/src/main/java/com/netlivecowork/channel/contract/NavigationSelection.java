package com.netlivecowork.channel.contract;
/** One server-owned selection per isolated interaction scope. */
public record NavigationSelection(String scopeRef, String sessionWorkspaceRef, String targetRef, long revision) {}
