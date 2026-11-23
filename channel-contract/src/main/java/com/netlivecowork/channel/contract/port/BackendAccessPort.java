package com.netlivecowork.channel.contract.port;

import com.netlivecowork.channel.contract.CommandOutcome;
import com.netlivecowork.channel.contract.CommandPacket;
import com.netlivecowork.channel.contract.FileMetadata;
import com.netlivecowork.channel.contract.FileReadResult;
import com.netlivecowork.channel.contract.OperationLookup;
import com.netlivecowork.channel.contract.PageRequest;
import com.netlivecowork.channel.contract.ReceiptPacket;
import com.netlivecowork.channel.contract.SessionWatch;
import com.netlivecowork.channel.contract.SessionWatchRequest;
import com.netlivecowork.channel.contract.TargetSummary;
import com.netlivecowork.channel.contract.TrustedCall;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * L2 → L3 的能力集合（l2-application.md §2.3）。公共包只引用 data-contracts.md，
 * 不新增通用包装；L3 不修改已受理的 CommandPacket。
 */
public interface BackendAccessPort {
    /** Recheck the current Cowork disclosure basis for one observed event. */
    default com.fasterxml.jackson.databind.JsonNode subscriptionEvent(String serviceRef, String ref, long revision,
                                                                     String eventId) {
        throw new com.netlivecowork.channel.contract.ChannelContractException(
            com.netlivecowork.channel.contract.ChannelErrorCode.UNSUPPORTED_CAPABILITY,
            "This backend does not support Cowork subscriptions");
    }

    /** Resolve an entry permission by reference at its CoWork host. */
    default com.fasterxml.jackson.databind.JsonNode entryPermission(
            String serviceRef, String permissionRef, String action, com.fasterxml.jackson.databind.JsonNode body) {
        throw new com.netlivecowork.channel.contract.ChannelContractException(
            com.netlivecowork.channel.contract.ChannelErrorCode.UNSUPPORTED_CAPABILITY,
            "This backend does not resolve Channel entry permissions");
    }

    default CompletionStage<List<com.netlivecowork.channel.contract.SessionWorkspace>> creationWorkspaces(
            TrustedCall call, String serviceRef) {
        return creationWorkspaces(call);
    }

    default CompletionStage<List<com.netlivecowork.channel.contract.SessionWorkspace>> creationWorkspaces(TrustedCall call) {
        return java.util.concurrent.CompletableFuture.completedFuture(List.of());
    }
    default CompletionStage<com.netlivecowork.channel.contract.SessionCreation.Created> createSession(
            TrustedCall call,String requestKey,String workspace,String prompt,boolean lookupOnly) {
        return java.util.concurrent.CompletableFuture.failedFuture(new com.netlivecowork.channel.contract.SessionCreation.NotStarted(
            com.netlivecowork.channel.contract.ChannelErrorCode.UNSUPPORTED_CAPABILITY,"Session creation is unavailable"));
    }

    default CompletionStage<com.netlivecowork.channel.contract.DeviceAccess.Ticket> issueDeviceEnrollment(
            com.netlivecowork.channel.contract.DeviceAccess.Authorization authorization) {
        return java.util.concurrent.CompletableFuture.failedFuture(new com.netlivecowork.channel.contract.ChannelContractException(
            com.netlivecowork.channel.contract.ChannelErrorCode.UNSUPPORTED_CAPABILITY,"Device enrollment is unavailable"));
    }
    default CompletionStage<Void> revokeDevice(String deviceRef, TrustedCall call) {
        return java.util.concurrent.CompletableFuture.failedFuture(new com.netlivecowork.channel.contract.ChannelContractException(
            com.netlivecowork.channel.contract.ChannelErrorCode.UNSUPPORTED_CAPABILITY,"Device management is unavailable"));
    }
    /** Durable original-operation barrier. A missing local record is not a not-sent proof. */
    default CompletionStage<Boolean> closeUnsent(CommandPacket command, TrustedCall call) {
        return java.util.concurrent.CompletableFuture.completedFuture(false);
    }

    /** Trusted internal history page; physical message IDs never pass directly to adapters. */
    default CompletionStage<com.netlivecowork.channel.contract.SessionHistory.BackendPage> queryHistory(
            TrustedCall call, String targetRef, PageRequest page) {
        return java.util.concurrent.CompletableFuture.failedFuture(new com.netlivecowork.channel.contract.ChannelContractException(
            com.netlivecowork.channel.contract.ChannelErrorCode.UNSUPPORTED_CAPABILITY, "backend history is unavailable"));
    }
    default java.util.concurrent.CompletionStage<com.netlivecowork.channel.contract.SessionAccess.Manager> verifySessionManager(String serviceRef,String protectedProof) {
        return java.util.concurrent.CompletableFuture.failedFuture(new com.netlivecowork.channel.contract.ChannelContractException(com.netlivecowork.channel.contract.ChannelErrorCode.UNSUPPORTED_CAPABILITY,"CoWork management authentication is unavailable"));
    }


    /** Existing complete interaction facts, verified by L3; the L2 event intake stays authoritative. */
    default CompletionStage<List<com.netlivecowork.channel.contract.VerifiedBackendEvent>> readInteractionEvents(
            String targetRef, TrustedCall call) {
        return java.util.concurrent.CompletableFuture.failedFuture(new com.netlivecowork.channel.contract.ChannelContractException(
            com.netlivecowork.channel.contract.ChannelErrorCode.UNSUPPORTED_CAPABILITY,"Interaction snapshots are unavailable"));
    }

    /** 提交已受理指令。返回派发阶段事实与已取得的回执，不冒充后端 accepted。 */
    CompletionStage<CommandOutcome> submitCommand(CommandPacket command, TrustedCall call);

    /** 查询类指令。返回携带查询结果的 ReceiptPacket，或明确的错误/未知事实。 */
    CompletionStage<ReceiptPacket> query(CommandPacket command, TrustedCall call);

    /** 回到原资源与原 requestKey 核对本次提交是否生效，不重新寻址或执行。 */
    CompletionStage<OperationLookup> lookupOperation(String operationRef, TrustedCall call);

    /** 可选逻辑目标查询。实际寻址规则保留在 L3。 */
    CompletionStage<List<TargetSummary>> listTargets(TrustedCall call, String agentRef, PageRequest page);

    /**
     * 资源归属事实（v2.11）：默认无事实可用；L3 实现后供 L2 作个人授权判断。
     * 该事实不是公开目标列表的一部分。
     */
    default CompletionStage<List<com.netlivecowork.channel.contract.TargetOwnership>> targetOwnerships(
        TrustedCall call) {
        return java.util.concurrent.CompletableFuture.completedFuture(List.of());
    }

    /** Configuration-only owner catalog, authenticated at the actual backend on every call. */
    default CompletionStage<com.netlivecowork.channel.contract.SessionAccess.Catalog> discoverOwnedSessions(TrustedCall call) {
        return java.util.concurrent.CompletableFuture.failedFuture(new com.netlivecowork.channel.contract.ChannelContractException(
            com.netlivecowork.channel.contract.ChannelErrorCode.UNSUPPORTED_CAPABILITY,"session access configuration is not supported"));
    }

    default CompletionStage<Void> registerCatalogTargets(TrustedCall call,String authenticationRef) {
        return java.util.concurrent.CompletableFuture.failedFuture(new com.netlivecowork.channel.contract.ChannelContractException(
            com.netlivecowork.channel.contract.ChannelErrorCode.UNSUPPORTED_CAPABILITY,"catalog registration is not supported"));
    }

    /** 会话级持续观察登记；后端不具备能力时返回 supported=false。 */
    CompletionStage<SessionWatch> ensureSessionWatch(SessionWatchRequest request);

    CompletionStage<Void> removeSessionWatch(String watchRef);

    /** 出站文件读取：按 fileRef 的 L3 不透明关联取回字节。 */
    CompletionStage<FileReadResult> readFile(String fileRef, String deliveryId);

    /**
     * L2 登记内部事项引用与后端事项身份的稳定映射（l2-application.md §2.2、l3-backend.md §5.3）。
     * 后端只认自己的事项 ID，L2 只认内部 interactionRef；该映射是 interaction.respond 的必备前提。
     */
    CompletionStage<Void> registerInteraction(String interactionRef, String targetRef,
                                              String backendInteractionId, long revision);

    /** L2 登记 fileRef 与后端文件的不透明关联（INV-42）。 */
    CompletionStage<Void> registerFile(String fileRef, String targetRef, String backendFileId,
                                       FileMetadata metadata);
}
