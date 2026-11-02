package com.netlivecowork.channel.l3;

/**
 * 后端服务登记。{@code baseUrl} 与 {@code credentialRef} 只存在于 L3（INV-11、52）；
 * credentialRef 是不透明引用，凭证原文经受限设施取得，永不进入业务包或日志。
 */
public record BackendService(String serviceRef, String tenantRef, String workspaceRef, String label,
                             String baseUrl, AccessType accessType, String credentialRef, boolean enabled, String deviceRef) {

    public BackendService {
        serviceRef = requireText(serviceRef, "serviceRef");
        tenantRef = requireText(tenantRef, "tenantRef");
        workspaceRef = requireText(workspaceRef, "workspaceRef");
        label = requireText(label, "label");
        accessType = accessType == null ? AccessType.CLOUD : accessType;
        if (accessType == AccessType.CONNECTOR) {
            deviceRef = requireText(deviceRef, "deviceRef");
            baseUrl = null;
        } else {
            baseUrl = requireText(baseUrl, "baseUrl");
            var uri = java.net.URI.create(baseUrl);
            boolean loopback = java.util.Set.of("127.0.0.1","localhost","[::1]","::1").contains(uri.getHost());
            if (uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || uri.getHost()==null
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback))
                || (accessType==AccessType.LOCAL && !loopback))
                throw new IllegalArgumentException("backend entry must be trusted HTTPS or a loopback development endpoint");
        }
        credentialRef = credentialRef == null || credentialRef.isBlank() ? null : credentialRef;
    }

    public BackendService(String serviceRef, String tenantRef, String workspaceRef, String label,
                          String baseUrl, AccessType accessType, String credentialRef, boolean enabled) {
        this(serviceRef,tenantRef,workspaceRef,label,baseUrl,accessType,credentialRef,enabled,null);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
