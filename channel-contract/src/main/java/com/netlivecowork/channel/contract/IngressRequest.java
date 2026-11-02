package com.netlivecowork.channel.contract;

/**
 * 入口待受理请求（L1 → L2）。identity 与 routing 引用 resolve 结果；不含 flowId、operationRef
 * 或后端地址（INV-09）。
 */
public record IngressRequest(String version, String requestId, Identity identity, Routing routing,
                             ChannelOperation operation) {

    public static final String SUPPORTED_VERSION = "1";

    public IngressRequest {
        if (!SUPPORTED_VERSION.equals(version)) {
            throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_VERSION,
                "unsupported ingress version: " + version);
        }
        Producer.requireText(requestId, "requestId");
        if (identity == null || routing == null || operation == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "ingress requires identity, routing and operation");
        }
    }

    /** 内部身份引用：entryRef 是接入实例，callerRef 是已解析主体。 */
    public record Identity(String entryRef, String callerRef) {
        public Identity {
            Producer.requireText(entryRef, "identity.entryRef");
            Producer.requireText(callerRef, "identity.callerRef");
        }
    }

    /** scopeRef 是协作范围；replyTargetRef 是默认回复目的地。 */
    public record Routing(String scopeRef, String replyTargetRef) {
        public Routing {
            scopeRef = scopeRef == null || scopeRef.isBlank() ? null : scopeRef;
            replyTargetRef = replyTargetRef == null || replyTargetRef.isBlank() ? null : replyTargetRef;
        }
    }
}
