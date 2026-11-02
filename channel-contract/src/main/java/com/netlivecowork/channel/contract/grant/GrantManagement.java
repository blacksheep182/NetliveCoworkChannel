package com.netlivecowork.channel.contract.grant;

import com.netlivecowork.channel.contract.ChannelError;
import com.netlivecowork.channel.contract.EventType;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * v2.11 个人授权管理契约（L2 §5.1）。Grant 只表示正向授权；显式拒绝、共享公开与管理权
 * 是独立策略，不由这些接口创建或解除。GrantView 是管理端 DTO，内部记录与失效数据不外泄。
 */
public final class GrantManagement {

    private GrantManagement() {
    }

    public record TrustedManagementContext(String tenantRef, String workspaceRef, String principalRef,
                                           String authenticationRef) {
    }

    public record GrantResource(String targetRef, long resourceEpoch, String taskRef) {
    }

    public sealed interface GrantPermission permits TargetRead, SessionSelect, InputSubmit, TaskRead,
        TaskCancel, InteractionRespond, EventsObserve, HistoryRead {
        String action();
    }

    public record TargetRead() implements GrantPermission {
        @Override
        public String action() {
            return "target.read";
        }
    }

    public record HistoryRead() implements GrantPermission {
        @Override public String action() { return "history.read"; }
    }

    public record SessionSelect() implements GrantPermission {
        @Override
        public String action() {
            return "session.select";
        }
    }

    public record InputSubmit() implements GrantPermission {
        @Override
        public String action() {
            return "input.submit";
        }
    }

    public record TaskRead() implements GrantPermission {
        @Override
        public String action() {
            return "task.read";
        }
    }

    public record TaskCancel() implements GrantPermission {
        @Override
        public String action() {
            return "task.cancel";
        }
    }

    public record InteractionRespond(List<String> responses, boolean allowArgumentEdit)
        implements GrantPermission {
        public InteractionRespond {
            responses = responses == null ? List.of() : List.copyOf(responses);
        }

        @Override
        public String action() {
            return "interaction.respond";
        }
    }

    public record EventsObserve(List<EventType> eventTypes) implements GrantPermission {
        public EventsObserve {
            eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
        }

        @Override
        public String action() {
            return "events.observe";
        }
    }

    public record GrantTerms(List<GrantPermission> permissions, Instant validFrom, Instant validUntil) {
        public GrantTerms {
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
        }
    }

    public record GrantSpec(GrantTerms terms, String granteePrincipalRef, GrantResource resource) {
    }

    public record CreateGrantRequest(String requestId, GrantSpec spec, String reason) {
    }

    public record ReplaceGrantRequest(String requestId, String grantRef, long expectedRevision,
                                      GrantTerms replacement, String reason) {
    }

    public record RevokeGrantRequest(String requestId, String grantRef, long expectedRevision, String reason) {
    }

    /** 管理端可读视图；enabled 仅表示未主动撤销，使用时仍校验期限/拒绝策略/管理依据。 */
    public record GrantView(String grantRef, GrantSpec spec, long revision, String recordState,
                            String createdBy, String updatedBy, Instant createdAt, Instant updatedAt,
                            Instant revokedAt) {
    }

    public record GrantMutationResult(String changeRef, String outcome, GrantView grant,
                                      Instant committedAt, boolean followUpRequired) {
    }

    public interface GrantMutationReply {
        record Committed(GrantMutationResult result) implements GrantMutationReply {
        }

        record Rejected(ChannelError error, Long currentRevision) implements GrantMutationReply {
        }

        record Unknown(String requestId, ChannelError error) implements GrantMutationReply {
        }
    }

    public interface GrantManagementPort {
        CompletionStage<GrantMutationReply> createGrant(TrustedManagementContext context,
                                                        CreateGrantRequest request);

        CompletionStage<GrantMutationReply> replaceGrant(TrustedManagementContext context,
                                                         ReplaceGrantRequest request);

        CompletionStage<GrantMutationReply> revokeGrant(TrustedManagementContext context,
                                                        RevokeGrantRequest request);

        CompletionStage<GrantView> getGrant(TrustedManagementContext context, String grantRef);
    }
}
