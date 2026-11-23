package com.netlivecowork.channel.contract;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * L1 已验证的平台事实（内部端口入参）。外部主体键的命名空间由 Adapter 类型、接入账号与身份
 * 绑定代次确定，不按外部字符串匹配用户（INV-03）。
 */
public record VerifiedIngress(String requestId, String externalAccountKey, Subject externalSubject,
                              ExternalScope externalScope, ExternalReplyTarget externalReplyTarget,
                              String sourceEventKey, String delegationProofRef) {

    public VerifiedIngress {
        Producer.requireText(requestId, "requestId");
        Producer.requireText(externalAccountKey, "externalAccountKey");
        if (externalSubject == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "externalSubject is required");
        }
        sourceEventKey = sourceEventKey == null || sourceEventKey.isBlank() ? null : sourceEventKey;
        delegationProofRef = delegationProofRef == null || delegationProofRef.isBlank() ? null : delegationProofRef;
    }

    public enum SubjectKind {
        USER, SERVICE
    }

    public record Subject(SubjectKind kind, String key) {
        public Subject {
            if (kind == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "subject.kind is required");
            }
            Producer.requireText(key, "subject.key");
        }
    }

    /** 定位范围的外部键。范围记录另存共享策略与成员授权（INV-04）。 */
    public record ExternalScope(String namespace, String key) {
        public ExternalScope {
            Producer.requireText(namespace, "scope.namespace");
            Producer.requireText(key, "scope.key");
        }
    }

    /** 回复位置：必须来自已验证协议事实或授权登记。 */
    public record ExternalReplyTarget(Audience audience, String key, JsonNode protocolData) {
        public static ExternalReplyTarget personalConversation(String key) {
            return new ExternalReplyTarget(Audience.PERSONAL, key,
                com.netlivecowork.channel.contract.json.Json.obj().put("conversation", key));
        }
        public ExternalReplyTarget {
            if (audience == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "reply.audience is required");
            }
            Producer.requireText(key, "reply.key");
        }
    }

    public enum Audience {
        PERSONAL, SHARED, SERVICE
    }
}
