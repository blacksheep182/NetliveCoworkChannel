package com.netlivecowork.channel.l3;

import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelErrorCode;

/**
 * 目标 → 实际服务/会话解析。只读可信登记：不按本次发言者重选服务、不隐式创建会话、
 * 不从输入推导 URL（INV-02、11、21、51）。
 */
final class TargetResolver {

    private final BackendRegistry registry;

    TargetResolver(BackendRegistry registry) {
        this.registry = registry;
    }

    /** 已固定的目标、服务与实际资源命名空间。 */
    record Resolved(TargetRegistration target, BackendService service) {
        String resourceRef() {
            return target.resourceRef();
        }
    }

    Resolved resolve(String targetRef, String tenantRef, String workspaceRef) {
        if (targetRef == null || targetRef.isBlank()) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "targetRef is required");
        }
        TargetRegistration target = registry.target(targetRef);
        if (target == null) {
            throw new ChannelContractException(ChannelErrorCode.IDENTITY_UNBOUND,
                "target is not registered: " + targetRef);
        }
        // 可信调用上下文必须与登记资源域一致；不接受客户端自报归属（INV-03）。
        if (!target.tenantRef().equals(tenantRef) || !target.workspaceRef().equals(workspaceRef)) {
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,
                "trusted call scope does not match the target registration");
        }
        if (!target.enabled()) {
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,
                "target is disabled: " + targetRef);
        }
        BackendService service = registry.service(target.serviceRef());
        if (service == null) {
            throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE,
                "backend service is not registered for the target");
        }
        if (!service.enabled()) {
            throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE,
                "backend service is disabled: " + service.serviceRef());
        }
        return new Resolved(target, service);
    }
}
