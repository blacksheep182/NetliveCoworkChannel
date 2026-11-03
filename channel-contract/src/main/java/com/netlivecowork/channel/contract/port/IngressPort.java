package com.netlivecowork.channel.contract.port;

import com.netlivecowork.channel.contract.ChannelResponse;
import com.netlivecowork.channel.contract.ChannelOperation;
import com.netlivecowork.channel.contract.IngressRequest;
import com.netlivecowork.channel.contract.IngressResolution;
import com.netlivecowork.channel.contract.VerifiedIngress;
import java.util.concurrent.CompletionStage;

/**
 * 实例限定的入口端口（L1 → L2）。resolve 只签发短期 admissionRef，不代表业务授权通过（INV-08）。
 */
public interface IngressPort {

    CompletionStage<IngressResolution> resolve(VerifiedIngress input);

    CompletionStage<ChannelResponse> submit(String admissionRef, IngressRequest request);

    CompletionStage<ChannelResponse> lookup(String admissionRef, String requestId);

    /** A platform-verified message completes an owner-initiated identity challenge. */
    default CompletionStage<Boolean> completeOwnerBinding(VerifiedIngress input, String token) {
        return completeOwnerWeixinBinding(input, token);
    }

    /** Legacy Weixin entry point, retained for existing adapters. */
    default CompletionStage<Boolean> completeOwnerWeixinBinding(VerifiedIngress input, String token) {
        return java.util.concurrent.CompletableFuture.failedFuture(new com.netlivecowork.channel.contract.ChannelContractException(
            com.netlivecowork.channel.contract.ChannelErrorCode.UNSUPPORTED_CAPABILITY, "Owner binding is unavailable"));
    }

    /** Adapter history capability is only a ceiling; L2 still checks identity and history.read. */
    default CompletionStage<com.netlivecowork.channel.contract.SessionHistory.Page> queryHistory(
            String admissionRef, com.netlivecowork.channel.contract.SessionHistory.Request request) {
        return java.util.concurrent.CompletableFuture.failedFuture(new com.netlivecowork.channel.contract.ChannelContractException(
            com.netlivecowork.channel.contract.ChannelErrorCode.UNSUPPORTED_CAPABILITY, "history query is unavailable"));
    }

    default CompletionStage<com.netlivecowork.channel.contract.SessionAccess.View> getSessionAccess(String admissionRef) {
        return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("session access"));
    }
    default CompletionStage<com.netlivecowork.channel.contract.SessionAccess.Saved> saveSessionAccess(String admissionRef,com.netlivecowork.channel.contract.SessionAccess.Save request) {
        return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("session access"));
    }
    default CompletionStage<com.netlivecowork.channel.contract.SessionAccess.Saved> lookupSessionAccess(String admissionRef,String requestId) {
        return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("session access"));
    }

    /** 还原预绑定动作；动态答案与草稿属于待补能力。 */
    CompletionStage<ChannelOperation> resolveAction(String admissionRef, String actionRef);
}
