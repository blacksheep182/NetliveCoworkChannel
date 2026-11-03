package com.netlivecowork.channel.l2;

import com.fasterxml.jackson.databind.JsonNode;
import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelError;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.ChannelOperation;
import com.netlivecowork.channel.contract.ChannelResponse;
import com.netlivecowork.channel.contract.IngressRequest;
import com.netlivecowork.channel.contract.IngressResolution;
import com.netlivecowork.channel.contract.InteractionRespondPayload;
import com.netlivecowork.channel.contract.InteractionResponse;
import com.netlivecowork.channel.contract.VerifiedIngress;
import com.netlivecowork.channel.contract.adapter.EntryConfig;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.infra.Digests;
import com.netlivecowork.channel.infra.Ids;
import com.netlivecowork.channel.l2.store.DirectoryRepository;
import com.netlivecowork.channel.l2.store.EntryRepository;
import com.netlivecowork.channel.l2.store.RequestRepository;
import java.time.Clock;

/**
 * 实例限定的身份解析与受理入口（INV-08、INV-12、INV-14）。admissionRef 只绑定接入、身份、范围、
 * 回复目标与请求标识，不代表业务授权通过。
 */
public final class AdmissionService {

    private final Clock clock;
    private final L2Settings settings;
    private final EntryRepository entries;
    private final DirectoryRepository directory;
    private final RequestRepository requests;
    private final SubmitService submitService;

    public AdmissionService(Clock clock, L2Settings settings, EntryRepository entries,
                            DirectoryRepository directory, RequestRepository requests,
                            SubmitService submitService) {
        this.clock = clock;
        this.settings = settings;
        this.entries = entries;
        this.directory = directory;
        this.requests = requests;
        this.submitService = submitService;
    }

    public IngressResolution resolve(EntryConfig entry, VerifiedIngress input) {
        if (!entry.enabled()) {
            throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE, "entry is disabled");
        }
        verifyAccount(entry, input.externalAccountKey());

        DirectoryRepository.IdentityBinding binding = directory.identity(entry.entryRef(),
            entry.accountBindingEpoch(), input.externalSubject().kind().name(),
            input.externalSubject().key());
        String callerRef;
        long bindingRevision;
        if (binding == null) {
            boolean verifiedGroupActor = input.externalScope() != null
                && input.externalReplyTarget() != null
                && input.externalReplyTarget().audience() == VerifiedIngress.Audience.SHARED
                && input.externalSubject().kind() == VerifiedIngress.SubjectKind.USER
                && input.externalScope().key().equals(input.externalReplyTarget().key())
                && java.util.Objects.equals(GroupWorkspaceGrantService.groupNamespace(entry),
                    input.externalScope().namespace());
            if (!settings.autoProvisionIdentities() && !verifiedGroupActor) {
                throw new ChannelContractException(ChannelErrorCode.IDENTITY_UNBOUND,
                    "external identity is not bound to an internal caller");
            }
            callerRef = "caller-" + shortHash(entry.entryRef(), String.valueOf(entry.accountBindingEpoch()),
                input.externalSubject().kind().name(), input.externalSubject().key());
            directory.bindIdentity(entry.entryRef(), entry.accountBindingEpoch(),
                input.externalSubject().kind().name(), input.externalSubject().key(), callerRef);
            bindingRevision = 1L;
        } else {
            callerRef = binding.callerRef();
            bindingRevision = binding.revision();
        }

        boolean sharedAudience = input.externalReplyTarget() != null
            && input.externalReplyTarget().audience() != VerifiedIngress.Audience.PERSONAL;
        // 个人范围/目的地把内部主体与绑定版本编进标识：换绑后旧标识不再可达（INV-54、55）。
        String personalScope = callerRef + "#" + bindingRevision;
        String scopeRef = null;
        if (input.externalScope() != null) {
            scopeRef = "scope-" + shortHash(entry.tenantRef(), entry.workspaceRef(), entry.entryRef(),
                String.valueOf(entry.accountBindingEpoch()), input.externalScope().namespace(),
                input.externalScope().key(), sharedAudience ? "" : personalScope);
            directory.ensureScope(new DirectoryRepository.ScopeRow(entry.tenantRef(), entry.workspaceRef(),
                scopeRef, entry.entryRef(), entry.accountBindingEpoch(),
                sharedAudience ? null : callerRef,
                sharedAudience ? null : input.externalSubject().kind().name(),
                sharedAudience ? null : input.externalSubject().key(),
                input.externalScope().namespace(), input.externalScope().key(),
                sharedAudience ? "SHARED" : "PERSONAL", sharedAudience));
        }

        String destinationRef = null;
        if (input.externalReplyTarget() != null) {
            String audience = input.externalReplyTarget().audience().name();
            JsonNode protocolData = input.externalReplyTarget().protocolData() == null
                ? Json.obj() : input.externalReplyTarget().protocolData();
            boolean personal = "PERSONAL".equals(audience);
            destinationRef = "dest-" + shortHash(entry.tenantRef(), entry.workspaceRef(), entry.entryRef(),
                String.valueOf(entry.accountBindingEpoch()), audience, input.externalReplyTarget().key(),
                personal ? personalScope : "");
            DirectoryRepository.DestinationRow existing = directory.destination(destinationRef);
            if (existing == null) {
                directory.upsertDestination(new DirectoryRepository.DestinationRow(destinationRef,
                    entry.tenantRef(), entry.workspaceRef(), entry.entryRef(), entry.accountBindingEpoch(),
                    personal ? callerRef : null,
                    personal ? input.externalSubject().kind().name() : null,
                    personal ? input.externalSubject().key() : null, audience, 1L, protocolData));
            } else if (!existing.protocolData().equals(protocolData)) {
                // 同一目的地身份的新协议数据：递增版本，旧积压不转发给新身份（INV-13）。
                directory.bumpDestinationRevision(destinationRef, audience, protocolData);
            }
        }

        String admissionRef = Ids.next("adm");
        directory.saveAdmission(new DirectoryRepository.AdmissionRow(admissionRef, entry.entryRef(), callerRef,
            scopeRef, destinationRef, input.externalAccountKey(),
            clock.instant().plus(settings.admissionTtl()), input.externalSubject().kind().name(),
            input.externalSubject().key(), bindingRevision, entry.accountBindingEpoch()));
        return new IngressResolution(admissionRef,
            new IngressRequest.Identity(entry.entryRef(), callerRef),
            new IngressRequest.Routing(scopeRef, destinationRef));
    }

    public ChannelResponse submit(EntryConfig entry, String admissionRef, IngressRequest request) {
        DirectoryRepository.AdmissionRow admission;
        try {
            admission = requireAdmission(entry, admissionRef);
        } catch (ChannelContractException e) {
            // admission 失效（过期、换绑）必须返回结构化拒绝，不能让异常穿透到平台入口。
            return new ChannelResponse.Rejected(request.requestId(), e.toError());
        }
        if (!admission.callerRef().equals(request.identity().callerRef())
            || !admission.entryRef().equals(request.identity().entryRef())) {
            return new ChannelResponse.Rejected(request.requestId(), ChannelError.of(
                ChannelErrorCode.PERMISSION_DENIED, "request identity does not match the admission"));
        }
        try {
            return submitService.submit(new SubmitService.Submission(entry, admission), request);
        } catch (ChannelContractException e) {
            return new ChannelResponse.Rejected(request.requestId(), e.toError());
        }
    }

    public ChannelResponse lookup(EntryConfig entry, String admissionRef, String requestId) {
        DirectoryRepository.AdmissionRow admission;
        try {
            admission = requireAdmission(entry, admissionRef);
        } catch (ChannelContractException e) {
            return new ChannelResponse.Unknown(requestId, null, e.toError());
        }
        try {
            return submitService.lookup(entry, admission.callerRef(), entry.accountBindingEpoch(), requestId);
        } catch (ChannelContractException e) {
            return new ChannelResponse.Unknown(requestId, null, e.toError());
        }
    }

    /** 还原预绑定动作；提交时重新验证实际操作者（INV-05）。 */
    public ChannelOperation resolveAction(EntryConfig entry, String admissionRef, String actionRef) {
        requireAdmission(entry, admissionRef);
        RequestRepository.ActionRow action = requests.action(actionRef);
        if (action == null || !action.entryRef().equals(entry.entryRef())) {
            throw new ChannelContractException(ChannelErrorCode.INTERACTION_STALE, "unknown action");
        }
        if (action.consumed()) {
            throw new ChannelContractException(ChannelErrorCode.INTERACTION_STALE, "action was already used");
        }
        if (action.expiresAt() != null && action.expiresAt().isBefore(clock.instant())) {
            throw new ChannelContractException(ChannelErrorCode.INTERACTION_STALE, "action has expired");
        }
        RequestRepository.InteractionRow interaction = requests.interaction(action.interactionRef());
        if (interaction == null || !"PENDING".equals(interaction.status())
            || interaction.revision() != action.revision()) {
            throw new ChannelContractException(ChannelErrorCode.INTERACTION_STALE,
                "the bound interaction is no longer pending");
        }
        InteractionResponse response = switch (InteractionPromptKind.of(action.responseKind())) {
            case APPROVE -> new InteractionResponse.Approve(null);
            case REJECT -> new InteractionResponse.Reject(null);
            case ANSWER -> throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CAPABILITY,
                "answer actions require dynamic input; submit an explicit interaction.respond");
        };
        return new ChannelOperation.InteractionRespond(new InteractionRespondPayload(
            interaction.interactionRef(), interaction.revision(), response));
    }

    private enum InteractionPromptKind {
        APPROVE, REJECT, ANSWER;

        static InteractionPromptKind of(String value) {
            try {
                return valueOf(value);
            } catch (IllegalArgumentException e) {
                return ANSWER;
            }
        }
    }

    com.netlivecowork.channel.contract.TrustedCall managementCall(EntryConfig entry,String admissionRef) {
        var a=requireAdmission(entry,admissionRef);
        return new com.netlivecowork.channel.contract.TrustedCall(entry.tenantRef(),entry.workspaceRef(),entry.entryRef(),a.callerRef(),null,
            entry.revision(),clock.instant().plusSeconds(30));
    }

    DirectoryRepository.AdmissionRow requireAdmission(EntryConfig entry, String admissionRef) {
        DirectoryRepository.AdmissionRow admission = directory.admission(admissionRef);
        if (admission == null || admission.expiresAt().isBefore(clock.instant())) {
            throw new ChannelContractException(ChannelErrorCode.REQUEST_NOT_FOUND,
                "admission is missing or expired; resolve again");
        }
        if (!admission.entryRef().equals(entry.entryRef())) {
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,
                "admission belongs to another entry");
        }
        // 精确复验原绑定引用与版本（S65、S66）：不得用 caller 的聚合版本兜底，也不得借同 caller
        // 的另一个绑定背书；账号代次变化同样使 admission 失效。
        if (admission.identityAccountBindingEpoch() != entry.accountBindingEpoch()) {
            throw new ChannelContractException(ChannelErrorCode.IDENTITY_UNBOUND,
                "account binding epoch changed since this admission was issued; resolve again");
        }
        String subjectKind = admission.identitySubjectKind();
        String subjectKey = admission.identitySubjectKey();
        if (subjectKind == null || subjectKey == null) {
            throw new ChannelContractException(ChannelErrorCode.IDENTITY_UNBOUND,
                "admission lacks the original identity binding; resolve again");
        }
        DirectoryRepository.IdentityBinding current = directory.identity(entry.entryRef(),
            entry.accountBindingEpoch(), subjectKind, subjectKey);
        if (current == null
            || !current.callerRef().equals(admission.callerRef())
            || current.revision() != admission.identityBindingRevision()) {
            throw new ChannelContractException(ChannelErrorCode.IDENTITY_UNBOUND,
                "identity binding changed since this admission was issued; resolve again");
        }
        return admission;
    }

    private void verifyAccount(EntryConfig entry, String externalAccountKey) {
        if (externalAccountKey == null || externalAccountKey.isBlank()) {
            throw new ChannelContractException(ChannelErrorCode.AUTH_REQUIRED,
                "external account is not verified");
        }
        String expected = entries.expectedExternalAccountKey(entry.entryRef());
        if (expected == null || expected.isBlank()) {
            entries.recordObservedAccount(entry.entryRef(), externalAccountKey);
            return;
        }
        if (!expected.equals(externalAccountKey)) {
            throw new ChannelContractException(ChannelErrorCode.ACCOUNT_MISMATCH,
                "verified platform account does not match the registered binding");
        }
    }

    private String shortHash(String... parts) {
        return Digests.sha256(String.join("\u0000", parts)).substring(0, 23);
    }
}
