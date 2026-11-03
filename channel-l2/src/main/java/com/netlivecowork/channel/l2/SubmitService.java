package com.netlivecowork.channel.l2;

import com.netlivecowork.channel.contract.Actor;
import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelError;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.ChannelMessage;
import com.netlivecowork.channel.contract.ChannelOperation;
import com.netlivecowork.channel.contract.ChannelResponse;
import com.netlivecowork.channel.contract.CommandPacket;
import com.netlivecowork.channel.contract.CommandPayload;
import com.netlivecowork.channel.contract.CommandType;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.Correlation;
import com.netlivecowork.channel.contract.IngressRequest;
import com.netlivecowork.channel.contract.InteractionPrompt;
import com.netlivecowork.channel.contract.InteractionRespondPayload;
import com.netlivecowork.channel.contract.InteractionResponse;
import com.netlivecowork.channel.contract.Layer;
import com.netlivecowork.channel.contract.OperationResult;
import com.netlivecowork.channel.contract.PacketContext;
import com.netlivecowork.channel.contract.Producer;
import com.netlivecowork.channel.contract.ReceiptPayload;
import com.netlivecowork.channel.contract.QuestionAnswer;
import com.netlivecowork.channel.contract.QuestionItem;
import com.netlivecowork.channel.contract.SubscriptionResource;
import com.netlivecowork.channel.contract.adapter.EntryConfig;
import com.netlivecowork.channel.contract.json.ContentJson;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.json.PacketJson;
import com.netlivecowork.channel.infra.Db;
import com.netlivecowork.channel.infra.Digests;
import com.netlivecowork.channel.infra.Ids;
import com.netlivecowork.channel.l2.store.DeliveryRepository;
import com.netlivecowork.channel.l2.store.DirectoryRepository;
import com.netlivecowork.channel.l2.store.RequestRepository;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 请求受理：去重、鉴权、逻辑目标选择、不可变规范指令与同事务的默认回复关系（INV-14～18）。
 * 网络调用一律在事务外执行（INV-20）。
 */
public final class SubmitService {

    private final NavigationService navigation;
    private final SessionCreationService creation;
    private final Db db;
    private final Clock clock;
    private final L2Settings settings;
    private final DirectoryRepository directory;
    private final RequestRepository requests;
    private final DeliveryRepository deliveries;
    private final CommandInterpreter interpreter;
    private final BackendDispatcher dispatcher;
    private final GrantService grants;
    private final GroupWorkspaceGrantService groupGrants;
    private final OwnerWeixinBindingService ownerWeixinBindings;
    private final com.netlivecowork.channel.contract.port.AdapterCatalog catalog;

    public SubmitService(Db db, Clock clock, L2Settings settings, DirectoryRepository directory,
                         RequestRepository requests, DeliveryRepository deliveries,
                         CommandInterpreter interpreter, BackendDispatcher dispatcher, GrantService grants,
                         com.netlivecowork.channel.contract.port.AdapterCatalog catalog,
                         GroupWorkspaceGrantService groupGrants,OwnerWeixinBindingService ownerWeixinBindings) {
        this.db = db;
        this.clock = clock;
        this.settings = settings;
        this.directory = directory;
        this.requests = requests;
        this.deliveries = deliveries;
        this.interpreter = interpreter;
        this.dispatcher = dispatcher;
        this.grants = grants;
        this.groupGrants = groupGrants;
        this.ownerWeixinBindings = ownerWeixinBindings;
        this.catalog = catalog;
        this.navigation = new NavigationService(db,directory,grants,dispatcher);
        this.creation = new SessionCreationService(db,clock,directory,dispatcher,grants,requests,groupGrants);
    }

    /** 受理上下文：已校验归属的接入与 admission（admission 不代表业务授权通过，INV-08）。 */
    public record Submission(EntryConfig entry, DirectoryRepository.AdmissionRow admission) {
    }

    public ChannelResponse submit(Submission submission, IngressRequest request) {
        EntryConfig entry = submission.entry();
        DirectoryRepository.AdmissionRow admission = submission.admission();
        String tenant = entry.tenantRef();
        String workspace = entry.workspaceRef();
        String scopeRef = request.routing().scopeRef() != null
            ? request.routing().scopeRef() : admission.scopeRef();
        if (admission.scopeRef() != null && !admission.scopeRef().equals(scopeRef)) {
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,
                "scope is outside the admission binding");
        }
        String replyTarget = request.routing().replyTargetRef() != null
            ? request.routing().replyTargetRef() : admission.replyTargetRef();
        if (admission.replyTargetRef() != null && !admission.replyTargetRef().equals(replyTarget)) {
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,
                "reply target is outside the admission binding");
        }
        DirectoryRepository.ScopeRow admittedScope = scopeRef == null ? null
            : directory.scope(tenant, workspace, scopeRef);
        if (admittedScope != null && admittedScope.shared()) {
            return submitShared(entry,admission,request,scopeRef,replyTarget,admittedScope);
        }

        String dedupeKey = dedupeKey(entry, admission, request.requestId());
        String requestDigest = Digests.sha256(Json.write(PacketJson.enc(request)));
        RequestRepository.InboxRow existing = requests.inboxByDedupe(dedupeKey);
        if (existing != null) {
            if (!existing.requestDigest().equals(requestDigest)) {
                throw new ChannelContractException(ChannelErrorCode.REQUEST_CONFLICT,
                    "requestId was already accepted with different content");
            }
            return replay(existing, request.requestId());
        }

        if(creation.exists(entry,admission.callerRef(),request.requestId()) && !(request.operation() instanceof ChannelOperation.SessionCreate)
            && !(request.operation() instanceof ChannelOperation.InputSubmit input && input.interpretation()==ChannelOperation.Interpretation.COMMAND_AWARE && input.content().stream().anyMatch(p->p instanceof ContentPart.Text text && text.text().strip().startsWith("/new "))))
            throw new ChannelContractException(ChannelErrorCode.REQUEST_CONFLICT,"Request ID belongs to a session creation");
        CommandInterpreter.Interpreted interpreted = interpreter.interpret(tenant, workspace, scopeRef, request);
        ChannelOperation operation = interpreted.operation();
        if ((operation instanceof ChannelOperation.SessionCreate || operation instanceof ChannelOperation.SelectionGet || operation instanceof ChannelOperation.SessionGet || operation instanceof ChannelOperation.SessionList || operation instanceof ChannelOperation.WorkspaceList || operation instanceof ChannelOperation.WorkspaceSelect)
            && AdapterQueryPolicy.access(catalog,entry)==com.netlivecowork.channel.contract.adapter.AdapterCapabilities.HistoryAccess.NONE)
            throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CAPABILITY,"This adapter does not expose session queries");
        // No backend IO inside local()'s write transaction.
        List<com.netlivecowork.channel.contract.SessionSummary> metadata =
            operation instanceof ChannelOperation.WorkspaceList || operation instanceof ChannelOperation.WorkspaceSelect || operation instanceof ChannelOperation.SessionList
                ? navigation.read(entry,admission.callerRef(),null)
                : operation instanceof ChannelOperation.SessionSelect select ? navigation.read(entry,admission.callerRef(),select.targetRef()) : List.of();
        if(operation instanceof ChannelOperation.SessionCreate create) {
            requirePrivateScope(tenant,workspace,scopeRef);
            return creation.submit(entry,admission,request,scopeRef,replyTarget,create,()->requireBindingCurrent(entry,admission),null);
        }
        return switch (operation) {
            case ChannelOperation.SelectionGet get -> local(entry,admission,request,scopeRef,get,()-> {
                requirePrivateScope(tenant,workspace,scopeRef);
                return new OperationResult.SelectionGet(navigation.current(entry,scopeRef,admission.callerRef()));
            });
            case ChannelOperation.WorkspaceList list -> local(entry,admission,request,scopeRef,list,()-> {
                requirePrivateScope(tenant,workspace,scopeRef);
                return navigation.list(entry,scopeRef,admission.callerRef(),metadata);
            });
            case ChannelOperation.WorkspaceSelect select -> local(entry,admission,request,scopeRef,select,()-> {
                requirePrivateScope(tenant,workspace,scopeRef);
                return navigation.select(entry,scopeRef,admission.callerRef(),select,metadata);
            });
            case ChannelOperation.SessionSelect select -> local(entry, admission, request, scopeRef, select,
                () -> selectResult(tenant, workspace, scopeRef, select, admission, metadata));
            case ChannelOperation.SessionList list -> local(entry, admission, request, scopeRef, list,
                () -> sessionListResult(list, entry, scopeRef, admission.callerRef(), metadata));
            case ChannelOperation.SubscriptionCreate create -> local(entry, admission, request, scopeRef, create,
                () -> subscribe(entry, admission, create, scopeRef));
            case ChannelOperation.SubscriptionRemove remove -> local(entry, admission, request, scopeRef, remove,
                () -> unsubscribe(tenant, workspace, remove, admission));
            default -> backend(entry, admission, request, scopeRef, replyTarget, operation,false);
        };
    }

    private ChannelResponse submitShared(EntryConfig entry,DirectoryRepository.AdmissionRow admission,
                                         IngressRequest request,String scopeRef,String replyTarget,
                                         DirectoryRepository.ScopeRow scope) {
        if(!"weixin".equals(entry.adapterType()) || !"weixin-group".equals(scope.namespace())
            || !"SHARED".equals(scope.audience()) || !entry.entryRef().equals(scope.entryRef())
            || scope.accountBindingEpoch()!=entry.accountBindingEpoch()
            || !"USER".equals(admission.identitySubjectKind()))
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Verified group scope is required");
        DirectoryRepository.DestinationRow destination=requireDestination(entry.tenantRef(),entry.workspaceRef(),replyTarget,entry.entryRef());
        if(!"SHARED".equals(destination.audience()) || destination.accountBindingEpoch()!=entry.accountBindingEpoch()
            || !scope.externalKey().equals(destination.protocolData().path("conversation").asText()))
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Group reply destination does not match the verified scope");
        var active=groupGrants.activeForScope(scopeRef);
        if(active.isEmpty())throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Group workspace is not open");
        String dedupe=dedupeKey(entry,admission,request.requestId());
        String digest=Digests.sha256(Json.write(PacketJson.enc(request)));
        var prior=requests.inboxByDedupe(dedupe);
        if(prior!=null) {
            if(!digest.equals(prior.requestDigest()))throw new ChannelContractException(ChannelErrorCode.REQUEST_CONFLICT,"Original group request changed");
            return replay(prior,request.requestId());
        }
        var operation=interpreter.interpret(entry.tenantRef(),entry.workspaceRef(),scopeRef,request).operation();
        if((operation instanceof ChannelOperation.SessionCreate || operation instanceof ChannelOperation.SessionList
            || operation instanceof ChannelOperation.SessionGet || operation instanceof ChannelOperation.WorkspaceList
            || operation instanceof ChannelOperation.WorkspaceSelect)
            && AdapterQueryPolicy.access(catalog,entry)==com.netlivecowork.channel.contract.adapter.AdapterCapabilities.HistoryAccess.NONE)
            throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CAPABILITY,"Group adapter does not expose session queries");
        GroupWorkspaceGrantService.Grant first=active.get(0);
        return switch(operation) {
            case ChannelOperation.WorkspaceList list -> local(entry,admission,request,scopeRef,list,()->{
                var workspaces=groupGrants.activeForScope(scopeRef).stream()
                    .map(g->new com.netlivecowork.channel.contract.SessionWorkspace(g.workspaceRef(),g.workspaceRef(),true)).toList();
                navigation.remember(scopeRef,"workspace",workspaces.stream().map(com.netlivecowork.channel.contract.SessionWorkspace::sessionWorkspaceRef).toList());
                return new OperationResult.WorkspaceList(workspaces,sharedSelection(entry,scopeRef,admission.callerRef()));
            },first);
            case ChannelOperation.WorkspaceSelect select -> {
                var grant=groupGrants.active(scopeRef,select.sessionWorkspaceRef());
                if(!groupGrants.current(grant))throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Workspace is not open to this group");
                yield local(entry,admission,request,scopeRef,select,()->{
                    var current=navigation.snapshot(entry,scopeRef);
                    if(current.revision()!=select.expectedBindingRevision())throw NavigationService.conflict();
                    if(!select.sessionWorkspaceRef().equals(current.sessionWorkspaceRef())
                        && !directory.compareAndSetSelection(entry.tenantRef(),entry.workspaceRef(),scopeRef,
                            select.sessionWorkspaceRef(),null,current.revision(),admission.callerRef()))throw NavigationService.conflict();
                    return new OperationResult.WorkspaceSelect(sharedSelection(entry,scopeRef,admission.callerRef()));
                },grant);
            }
            case ChannelOperation.SelectionGet get -> local(entry,admission,request,scopeRef,get,
                ()->new OperationResult.SelectionGet(sharedSelection(entry,scopeRef,admission.callerRef())),first);
            case ChannelOperation.SessionList list -> {
                var metadata=groupMetadata(entry,scopeRef,first.ownerRef());
                yield local(entry,admission,request,scopeRef,list,
                    ()->sessionListResult(list,entry,scopeRef,first.ownerRef(),metadata),first);
            }
            case ChannelOperation.SessionSelect select -> {
                var grant=groupGrants.forTarget(scopeRef,select.targetRef());
                groupGrants.requireTarget(grant,scopeRef,select.targetRef());
                var metadata=dispatcher.readSession(entry,grant.ownerRef(),select.targetRef());
                yield local(entry,admission,request,scopeRef,select,()->{
                    var priorSelection=navigation.snapshot(entry,scopeRef);
                    if(priorSelection.revision()!=select.expectedBindingRevision())throw NavigationService.conflict();
                    if(priorSelection.sessionWorkspaceRef()!=null
                        && !priorSelection.sessionWorkspaceRef().equals(grant.workspaceRef()))throw NavigationService.conflict();
                    if(metadata.workspace()==null || !grant.workspaceRef().equals(metadata.workspace().sessionWorkspaceRef()))
                        throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Group session workspace changed");
                    if(!directory.compareAndSetSelection(entry.tenantRef(),entry.workspaceRef(),scopeRef,
                        grant.workspaceRef(),select.targetRef(),select.expectedBindingRevision(),admission.callerRef()))throw NavigationService.conflict();
                    var selected=navigation.snapshot(entry,scopeRef);
                    return new OperationResult.SessionSelect(scopeRef,select.targetRef(),selected.revision(),grant.workspaceRef());
                },grant);
            }
            case ChannelOperation.SessionCreate create -> {
                var grant=groupGrants.active(scopeRef,create.sessionWorkspaceRef());
                if(!groupGrants.current(grant))throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Workspace is not open to this group");
                yield creation.submit(entry,admission,request,scopeRef,replyTarget,create,
                    ()->requireBindingCurrent(entry,admission),grant);
            }
            case ChannelOperation.SubscriptionCreate ignored -> throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CAPABILITY,"Group subscriptions are unavailable");
            case ChannelOperation.SubscriptionRemove ignored -> throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CAPABILITY,"Group subscriptions are unavailable");
            default -> backend(entry,admission,request,scopeRef,replyTarget,operation,true);
        };
    }

    private com.netlivecowork.channel.contract.NavigationSelection sharedSelection(EntryConfig entry,String scopeRef,String sender) {
        var selected=navigation.snapshot(entry,scopeRef);
        String workspace=selected.sessionWorkspaceRef(),target=selected.targetRef();
        var grant=workspace==null?null:groupGrants.active(scopeRef,workspace);
        if(!groupGrants.current(grant))return new com.netlivecowork.channel.contract.NavigationSelection(scopeRef,null,null,selected.revision());
        if(target!=null)try{groupGrants.requireTarget(grant,scopeRef,target);}
            catch(ChannelContractException ignored){target=null;}
        return new com.netlivecowork.channel.contract.NavigationSelection(scopeRef,workspace,target,selected.revision());
    }

    private List<com.netlivecowork.channel.contract.SessionSummary> groupMetadata(EntryConfig entry,String scopeRef,String owner) {
        var result=new java.util.ArrayList<com.netlivecowork.channel.contract.SessionSummary>();
        for(String target:groupGrants.targets(scopeRef)) {
            var grant=groupGrants.forTarget(scopeRef,target);
            if(grant==null || !groupGrants.current(grant))continue;
            var item=dispatcher.readSession(entry,owner,target);
            if(item.workspace()!=null && grant.workspaceRef().equals(item.workspace().sessionWorkspaceRef()))result.add(item);
        }
        return List.copyOf(result);
    }

    public ChannelResponse lookup(EntryConfig entry, String callerRef, long epoch, String requestId) {
        var created=creation.lookup(entry,callerRef,requestId);if(created!=null)return created;
        RequestRepository.InboxRow row = requests.inboxByRequest(entry.entryRef(), epoch, callerRef, requestId);
        if (row == null) {
            return new ChannelResponse.Unknown(requestId, null, ChannelError.of(
                ChannelErrorCode.REQUEST_NOT_FOUND, "no accepted record for this requestId"));
        }
        return replay(row, requestId);
    }

    private ChannelResponse replay(RequestRepository.InboxRow row, String requestId) {
        String groupScope=groupGrants.operationScope(row.operationRef());
        if(groupScope!=null && !groupGrants.currentOperation(row.operationRef()))
            return new ChannelResponse.Rejected(requestId,ChannelErrorCode.PERMISSION_DENIED.error("Group workspace grant was revoked"));
        if (row.responseJson() != null) {
            ChannelResponse response = PacketJson.decChannelResponse(row.responseJson());
            if (response instanceof ChannelResponse.Accepted accepted
                && row.status() == RequestRepository.OperationStatus.DISPATCHED) {
                // Migrate the projection of older accepted receipts using the persisted dispatch fact,
                // never by inferring acceptance from the current session state.
                response = new ChannelResponse.Accepted(requestId, accepted.operationRef(), accepted.taskRef(),
                    ReceiptPayload.AcceptedBy.AGENT);
            }
            if (response instanceof ChannelResponse.Completed completed) {
                if(groupScope!=null) {
                    var result=completed.result();
                    if(result instanceof OperationResult.SessionList list)
                        return new ChannelResponse.Completed(requestId,new OperationResult.SessionList(
                            list.items().stream().filter(item->groupGrants.forTarget(groupScope,item.targetRef())!=null).toList(),
                            list.nextCursor()));
                    if(result instanceof OperationResult.WorkspaceList list) {
                        var allowed=groupGrants.activeForScope(groupScope).stream().map(GroupWorkspaceGrantService.Grant::workspaceRef).toList();
                        return new ChannelResponse.Completed(requestId,new OperationResult.WorkspaceList(
                            list.items().stream().filter(item->allowed.contains(item.sessionWorkspaceRef())).toList(),
                            safeGroupSelection(groupScope,list.selection())));
                    }
                    if(result instanceof OperationResult.SelectionGet selected)
                        return new ChannelResponse.Completed(requestId,new OperationResult.SelectionGet(
                            safeGroupSelection(groupScope,selected.selection())));
                    if(result instanceof OperationResult.WorkspaceSelect selected)
                        return new ChannelResponse.Completed(requestId,new OperationResult.WorkspaceSelect(
                            safeGroupSelection(groupScope,selected.selection())));
                    if(result instanceof OperationResult.SessionCreate created
                        && groupGrants.forTarget(groupScope,created.session().targetRef())==null)
                        return new ChannelResponse.Rejected(requestId,ChannelErrorCode.PERMISSION_DENIED.error("Group session origin is unavailable"));
                    if(row.status()!=RequestRepository.OperationStatus.LOCAL_DONE && !grants.current(row.operationRef()))
                        return new ChannelResponse.Rejected(requestId,ChannelErrorCode.PERMISSION_DENIED.error("Original owner authorization changed"));
                    return response;
                }
                if(completed.result() instanceof OperationResult.SessionCreate create && !grants.canAct(row.tenantRef(),row.workspaceRef(),row.callerRef(),create.session().targetRef(),"target.read"))
                    return new ChannelResponse.Rejected(requestId,ChannelErrorCode.PERMISSION_DENIED.error("Created session access was revoked"));
                if (completed.result() instanceof OperationResult.WorkspaceList list) {
                    var allowed=navigation.visibleWorkspaces(row.tenantRef(),row.workspaceRef(),row.callerRef());
                    var selection=list.selection();
                    if(selection.sessionWorkspaceRef()!=null && !allowed.contains(selection.sessionWorkspaceRef()))
                        return new ChannelResponse.Rejected(requestId,ChannelErrorCode.PERMISSION_DENIED.error("Original workspace authorization was revoked; query current selection"));
                    if(selection.targetRef()!=null && !grants.canAct(row.tenantRef(),row.workspaceRef(),row.callerRef(),selection.targetRef(),"target.read"))
                        return new ChannelResponse.Rejected(requestId,ChannelErrorCode.PERMISSION_DENIED.error("Original selection authorization was revoked; query current selection"));
                    return new ChannelResponse.Completed(requestId,new OperationResult.WorkspaceList(list.items().stream().filter(w->allowed.contains(w.sessionWorkspaceRef())).toList(),selection));
                }
                if (completed.result() instanceof OperationResult.SelectionGet get) {
                    var selected=get.selection();
                    if(selected.sessionWorkspaceRef()!=null && !navigation.visibleWorkspaces(row.tenantRef(),row.workspaceRef(),row.callerRef()).contains(selected.sessionWorkspaceRef())
                        || selected.targetRef()!=null && !grants.canAct(row.tenantRef(),row.workspaceRef(),row.callerRef(),selected.targetRef(),"target.read"))
                        return new ChannelResponse.Rejected(requestId,ChannelErrorCode.PERMISSION_DENIED.error("Original selection authorization was revoked; query current selection"));
                }
                if (completed.result() instanceof OperationResult.SessionSelect select && !grants.canAct(row.tenantRef(),row.workspaceRef(),row.callerRef(),select.targetRef(),"target.read"))
                    return new ChannelResponse.Rejected(requestId,ChannelErrorCode.PERMISSION_DENIED.error("Original selection authorization was revoked"));
                if (completed.result() instanceof OperationResult.WorkspaceSelect select && !navigation.visibleWorkspaces(row.tenantRef(),row.workspaceRef(),row.callerRef()).contains(select.selection().sessionWorkspaceRef()))
                    return new ChannelResponse.Rejected(requestId,ChannelErrorCode.PERMISSION_DENIED.error("Original workspace authorization was revoked"));
                if (completed.result() instanceof OperationResult.SessionList list) {
                    return new ChannelResponse.Completed(requestId, new OperationResult.SessionList(
                        list.items().stream().filter(item -> groupScope!=null
                            ? groupGrants.forTarget(groupScope,item.targetRef())!=null
                            : grants.canAct(row.tenantRef(), row.workspaceRef(),row.callerRef(), item.targetRef(), "target.read"))
                            .toList(), list.nextCursor()));
                }
                if (row.status() != RequestRepository.OperationStatus.LOCAL_DONE && !grants.current(row.operationRef()))
                    return new ChannelResponse.Rejected(requestId, ChannelError.of(ChannelErrorCode.PERMISSION_DENIED,
                        "original result authorization is no longer valid"));
            }
            return response;
        }
        return new ChannelResponse.Accepted(requestId, row.operationRef(), null);
    }

    private com.netlivecowork.channel.contract.NavigationSelection safeGroupSelection(String scope,
            com.netlivecowork.channel.contract.NavigationSelection selection) {
        if(selection==null)return null;
        var grant=selection.sessionWorkspaceRef()==null?null:groupGrants.active(scope,selection.sessionWorkspaceRef());
        if(!groupGrants.current(grant))return new com.netlivecowork.channel.contract.NavigationSelection(scope,null,null,selection.revision());
        if(selection.targetRef()!=null && groupGrants.forTarget(scope,selection.targetRef())==null)
            return new com.netlivecowork.channel.contract.NavigationSelection(scope,selection.sessionWorkspaceRef(),null,selection.revision());
        return selection;
    }

    private String dedupeKey(EntryConfig entry, DirectoryRepository.AdmissionRow admission, String requestId) {
        return requestKey(entry,admission.callerRef(),requestId);
    }

    static String requestKey(EntryConfig entry,String caller,String requestId) {
        return String.join("\u0000",entry.tenantRef(),entry.workspaceRef(),entry.entryRef(),String.valueOf(entry.accountBindingEpoch()),caller,requestId);
    }

    // ---- 本地操作（L2 执行） ----------------------------------------------------------

    private OperationResult selectResult(String tenant, String workspace, String scopeRef,
                                         ChannelOperation.SessionSelect select,
                                         DirectoryRepository.AdmissionRow admission, List<com.netlivecowork.channel.contract.SessionSummary> metadata) {
        if (scopeRef == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "session.select requires a scope");
        }
        DirectoryRepository.ScopeRow scope = requireScope(tenant, workspace, scopeRef);
        DirectoryRepository.TargetRow target = requireTarget(tenant, workspace, select.targetRef(), admission.callerRef(), "session.select");
        requireTarget(tenant, workspace, select.targetRef(), admission.callerRef(), "target.read");
        if (scope.shared()) throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED, "verified shared membership is required");
        var item=metadata.stream().filter(s->select.targetRef().equals(s.targetRef())).findFirst()
            .orElseThrow(()->new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Session metadata is not authorized"));
        var prior=directory.binding(tenant,workspace,scopeRef);
        String sessionWorkspace=item.workspace()==null?null:item.workspace().sessionWorkspaceRef();
        if(prior!=null && prior.sessionWorkspaceRef()!=null && !prior.sessionWorkspaceRef().equals(sessionWorkspace))
            throw new ChannelContractException(ChannelErrorCode.BINDING_CONFLICT,"Select the session's workspace first");
        boolean updated = directory.compareAndSetSelection(tenant, workspace, scopeRef, sessionWorkspace,target.targetRef(),
            select.expectedBindingRevision(), admission.callerRef());
        if (!updated) {
            throw new ChannelContractException(ChannelErrorCode.BINDING_CONFLICT,
                "scope binding revision changed; re-read and choose again");
        }
        DirectoryRepository.BindingRow binding = directory.binding(tenant, workspace, scopeRef);
        return new OperationResult.SessionSelect(scopeRef, target.targetRef(),
            binding == null ? 1L : binding.revision(), sessionWorkspace);
    }

    private void requirePrivateScope(String tenant,String workspace,String scope) {
        if(scope==null)throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,"Navigation requires an interaction scope");
        if(requireScope(tenant,workspace,scope).shared())throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Verified shared membership is required");
    }

    private OperationResult subscribe(EntryConfig entry, DirectoryRepository.AdmissionRow admission,
                                      ChannelOperation.SubscriptionCreate create, String scopeRef) {
        String tenant = entry.tenantRef();
        String workspace = entry.workspaceRef();
        DirectoryRepository.DestinationRow destination = directory.destination(create.destinationRef());
        if (destination == null || !destination.tenantRef().equals(tenant)
            || !destination.workspaceRef().equals(workspace)) {
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,
                "destination is not available to this caller");
        }
        if (!destination.entryRef().equals(entry.entryRef())) {
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,
                "destination belongs to another entry");
        }
        DirectoryRepository.ScopeRow scope = scopeRef == null ? null : directory.scope(tenant, workspace, scopeRef);
        if ("SHARED".equals(destination.audience())) {
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,
                "shared delivery requires an explicit shared scope authorization");
        }
        if (create.resource() instanceof SubscriptionResource.Task task) {
            RequestRepository.TaskRow row = requests.task(task.taskRef());
            if (row == null || !row.tenantRef().equals(tenant) || !row.workspaceRef().equals(workspace)) {
                throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED, "unknown task");
            }
        } else if (create.resource() instanceof SubscriptionResource.Session session) {
            requireTarget(tenant, workspace, session.targetRef(), admission.callerRef(), "events.observe");
            // 会话级持续观察必须先在后端持久登记观察范围与起点，未完成登记不宣称订阅生效（INV-65）。
            // 该流程需要“先记 pending 意图 → L3 登记 → 激活”的两段协调，当前版本尚未接线。
            throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CAPABILITY,
                "session-level subscription requires backend observation registration, which is not enabled");
        }
        if (create.resource() instanceof SubscriptionResource.Task)
            throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CAPABILITY,
                "task subscriptions require durable observation authorization, which is not enabled");
        String subscriptionRef = Ids.next("sub");
        String resourceRef = create.resource() instanceof SubscriptionResource.Task task
            ? task.taskRef() : ((SubscriptionResource.Session) create.resource()).targetRef();
        deliveries.saveSubscription(new DeliveryRepository.SubscriptionRow(subscriptionRef, tenant, workspace,
            admission.callerRef(), create.resource().type(), resourceRef,
            create.eventTypes().stream().map(Enum::name).toList(), entry.entryRef(),
            destination.destinationRef(), destination.destinationRevision(), "ACTIVE", 1L, 1L,
            create.resumeCursor(), null, null));
        return new OperationResult.SubscriptionCreate(subscriptionRef);
    }

    private OperationResult unsubscribe(String tenant, String workspace,
                                        ChannelOperation.SubscriptionRemove remove,
                                        DirectoryRepository.AdmissionRow admission) {
        DeliveryRepository.SubscriptionRow row = deliveries.subscription(remove.subscriptionRef());
        if (row == null || !row.tenantRef().equals(tenant) || !row.workspaceRef().equals(workspace)) {
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED, "unknown subscription");
        }
        if (!row.subscriberRef().equals(admission.callerRef())) {
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,
                "only the subscriber can remove this subscription");
        }
        deliveries.updateSubscriptionStatus(row.subscriptionRef(), "CANCELLED", row.revision() + 1, null, null);
        deliveries.deactivateRelations(DeliveryRepository.RelationType.SUBSCRIPTION, row.subscriptionRef());
        deliveries.cancelUnbackedDeliveries();
        return new OperationResult.SubscriptionRemove(row.subscriptionRef());
    }

    private DirectoryRepository.ScopeRow requireScope(String tenant, String workspace, String scopeRef) {
        DirectoryRepository.ScopeRow scope = directory.scope(tenant, workspace, scopeRef);
        if (scope == null) {
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED, "unknown scope");
        }
        return scope;
    }

    private DirectoryRepository.TargetRow requireTarget(String tenant, String workspace, String targetRef,
                                                        String callerRef, String action) {
        DirectoryRepository.TargetRow target = directory.target(targetRef);
        if (target == null || !target.tenantRef().equals(tenant) || !target.workspaceRef().equals(workspace)) {
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,
                "target is not available to this caller");
        }
        // 有可信归属时：所有者本人，或覆盖该动作的有效个人 Grant 才放行（v2.11 §2.4）。
        String owner = directory.targetOwner(targetRef);
        if (!grants.canAct(tenant, workspace, callerRef, targetRef, action)) {
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,
                "caller is not authorized for this target action (owner=" + owner + ", action=" + action + ")");
        }
        return target;
    }

    private void requireBoundCaller(String callerRef) {
        if (callerRef == null || callerRef.isBlank()) {
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED, "caller is not bound");
        }
    }

    /** 事务内复验原绑定仍有效（同 entry、账号代次、绑定版本与 caller）。 */
    private void requireBindingCurrent(EntryConfig entry, DirectoryRepository.AdmissionRow admission) {
        if (admission.identityAccountBindingEpoch() != entry.accountBindingEpoch()) {
            throw new ChannelContractException(ChannelErrorCode.IDENTITY_UNBOUND,
                "account binding epoch changed since this admission was issued");
        }
        DirectoryRepository.IdentityBinding current = directory.identity(entry.entryRef(),
            admission.identityAccountBindingEpoch(), admission.identitySubjectKind(),
            admission.identitySubjectKey());
        if (current == null
            || !current.callerRef().equals(admission.callerRef())
            || current.revision() != admission.identityBindingRevision()) {
            throw new ChannelContractException(ChannelErrorCode.IDENTITY_UNBOUND,
                "identity binding changed while the operation was being accepted");
        }
    }

    // ---- 本地操作落库 ----------------------------------------------------------------

    private ChannelResponse local(EntryConfig entry, DirectoryRepository.AdmissionRow admission,
                                  IngressRequest request, String scopeRef, ChannelOperation operation,
                                  Supplier<OperationResult> work) {
        return local(entry,admission,request,scopeRef,operation,work,null);
    }

    private ChannelResponse local(EntryConfig entry, DirectoryRepository.AdmissionRow admission,
                                  IngressRequest request, String scopeRef, ChannelOperation operation,
                                  Supplier<OperationResult> work,GroupWorkspaceGrantService.Grant groupGrant) {
        String flowId = Ids.next("flow");
        String operationRef = Ids.next("op");
        String packetId = Ids.next("cmd");
        String dedupeKey = dedupeKey(entry, admission, request.requestId());
        String requestDigest = Digests.sha256(Json.write(PacketJson.enc(request)));
        ChannelResponse response = db.tx(connection -> {
            requireBindingCurrent(entry, admission);
            if(groupGrant!=null && !groupGrants.current(groupGrant))
                throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Group workspace grant was revoked");
            var prior=requests.inboxByDedupe(dedupeKey);
            if(prior!=null) {
                if(!prior.requestDigest().equals(requestDigest))throw new ChannelContractException(ChannelErrorCode.REQUEST_CONFLICT,"Request content changed");
                return replay(prior,request.requestId());
            }
            OperationResult result = work.get();
            CommandType type = CommandType.ofOperation(operation);
            CommandPayload payload = localPayload(operation, scopeRef);
            PacketContext context = new PacketContext(entry.tenantRef(), entry.workspaceRef(),
                operation instanceof ChannelOperation.SessionSelect select?select.targetRef():null, operationRef, null);
            CommandPacket packet = new CommandPacket("1", packetId, type, clock.instant(),
                Correlation.of(flowId), context, new Producer(Layer.L2, settings.componentRef()),
                Actor.of(admission.callerRef()), payload);
            ChannelResponse projection = new ChannelResponse.Completed(request.requestId(), result);
            requests.saveInbox(new RequestRepository.InboxRow(dedupeKey, entry.tenantRef(),
                entry.workspaceRef(), entry.entryRef(), entry.accountBindingEpoch(), admission.callerRef(),
                request.requestId(), requestDigest, packetId, operationRef, flowId, type.name(),
                Json.write(PacketJson.enc(packet)), RequestRepository.OperationStatus.LOCAL_DONE,
                Json.write(PacketJson.enc(projection)), 0, null, clock.instant(),
                admission.identitySubjectKind(), admission.identitySubjectKey(),
                admission.identityBindingRevision()));
            if(groupGrant!=null)groupGrants.recordOperation(operationRef,groupGrant,
                operation instanceof ChannelOperation.SessionSelect select?select.targetRef():null,
                admission.identitySubjectKey(),type.name());
            return projection;
        });
        return response;
    }

    private CommandPayload localPayload(ChannelOperation operation, String scopeRef) {
        return switch (operation) {
            case ChannelOperation.SessionSelect select -> new CommandPayload.SessionSelect(
                scopeRef == null ? "" : scopeRef, select.expectedBindingRevision());
            case ChannelOperation.SessionList list -> new CommandPayload.SessionList(list.agentRef(), list.page(),list.sessionWorkspaceRef());
            case ChannelOperation.SelectionGet ignored -> new CommandPayload.SelectionGet();
            case ChannelOperation.WorkspaceList ignored -> new CommandPayload.WorkspaceList();
            case ChannelOperation.WorkspaceSelect select -> new CommandPayload.WorkspaceSelect(scopeRef,select.sessionWorkspaceRef(),select.expectedBindingRevision());
            case ChannelOperation.SubscriptionCreate create -> {
                DirectoryRepository.DestinationRow destination = directory.destination(
                    create.destinationRef());
                yield new CommandPayload.SubscriptionCreate(create.resource(), create.eventTypes(),
                    create.destinationRef(), destination == null ? 0L : destination.destinationRevision(),
                    create.resumeCursor());
            }
            case ChannelOperation.SubscriptionRemove remove ->
                new CommandPayload.SubscriptionRemove(remove.subscriptionRef());
            default -> throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CAPABILITY,
                "operation is not executed locally: " + operation.type());
        };
    }

    // ---- 后端操作 --------------------------------------------------------------------

    private ChannelResponse backend(EntryConfig entry, DirectoryRepository.AdmissionRow admission,
                                    IngressRequest request, String scopeRef, String replyTarget,
                                    ChannelOperation operation,boolean shared) {
        String tenant = entry.tenantRef();
        String workspace = entry.workspaceRef();
        CommandType type = CommandType.ofOperation(operation);
        String flowId = Ids.next("flow");
        String causationId = null;
        String targetRef = null;
        String taskRef = null;
        CommandPayload payload;
        Long selectionRevision=null;
        GroupWorkspaceGrantService.Grant groupGrant=null;

        switch (operation) {
            case ChannelOperation.InputSubmit submit -> {
                var currentSelection=directory.binding(tenant,workspace,scopeRef);
                selectionRevision=submit.expectedBindingRevision()!=null?submit.expectedBindingRevision():currentSelection==null?null:currentSelection.revision();
                targetRef = submit.targetRef() != null ? submit.targetRef()
                    : boundTarget(tenant, workspace, scopeRef);
                if (targetRef == null) {
                    throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                        "no target selected for this scope; use /use or pass an explicit target");
                }
                if(shared) {
                    groupGrant=groupGrants.forTarget(scopeRef,targetRef);
                    groupGrants.requireTarget(groupGrant,scopeRef,targetRef);
                }
                String principal=shared?groupGrant.ownerRef():admission.callerRef();
                requireTarget(tenant, workspace, targetRef, principal, "input.submit");
                requireTarget(tenant, workspace, targetRef, principal, "target.read");
                requireBoundCaller(admission.callerRef());
                DirectoryRepository.DestinationRow destination = requireDestination(tenant, workspace,
                    replyTarget, entry.entryRef());
                DirectoryRepository.ScopeRow scope = scopeRef == null
                    ? null : directory.scope(tenant, workspace, scopeRef);
                if ("SHARED".equals(destination.audience()) && !shared) {
                    throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,
                        "shared delivery requires an explicit shared scope authorization");
                }
                if(shared && !"SHARED".equals(destination.audience()))
                    throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Group delivery requires a group destination");
                ChannelMessage message = new ChannelMessage(Ids.next("msg"), submit.content(),
                    submit.replyToMessageRef());
                validateReferences(message);
                payload = new CommandPayload.InputSubmit(message, submit.interpretation());
            }
            case ChannelOperation.SessionList list ->
                payload = new CommandPayload.SessionList(list.agentRef(), list.page());
            case ChannelOperation.SessionGet get -> {
                targetRef = get.targetRef();
                if(shared) {
                    groupGrant=groupGrants.forTarget(scopeRef,targetRef);
                    groupGrants.requireTarget(groupGrant,scopeRef,targetRef);
                }
                requireTarget(tenant, workspace, targetRef, shared?groupGrant.ownerRef():admission.callerRef(), "target.read");
                payload = new CommandPayload.SessionGet();
            }
            case ChannelOperation.SessionResume resume -> {
                targetRef = resume.targetRef();
                if(shared) {
                    groupGrant=groupGrants.forTarget(scopeRef,targetRef);
                    groupGrants.requireTarget(groupGrant,scopeRef,targetRef);
                }
                String principal=shared?groupGrant.ownerRef():admission.callerRef();
                requireTarget(tenant,workspace,targetRef,principal,"target.read");
                requireTarget(tenant,workspace,targetRef,principal,"input.submit");
                payload = new CommandPayload.SessionResume();
            }
            case ChannelOperation.TaskGet get -> {
                RequestRepository.TaskRow task = requireTask(tenant, workspace, get.taskRef());
                taskRef = task.taskRef();
                targetRef = task.targetRef();
                flowId = task.flowId();
                payload = new CommandPayload.TaskGet();
            }
            case ChannelOperation.TaskCancel cancel -> {
                RequestRepository.TaskRow task = requireTask(tenant, workspace, cancel.taskRef());
                if (task.status().terminal()) {
                    throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                        "task is already finished");
                }
                taskRef = task.taskRef();
                targetRef = task.targetRef();
                flowId = task.flowId();
                payload = new CommandPayload.TaskCancel(cancel.reason());
            }
            case ChannelOperation.InteractionRespond respond -> {
                RequestRepository.InteractionRow interaction = requireInteraction(tenant, workspace, respond);
                validateInteractionResponse(interaction, respond.payload());
                // 领取（CAS）在受理事务内完成，这里只做校验，避免领取与受理分离。
                flowId = interaction.flowId();
                causationId = interaction.originPacketId();
                targetRef = interaction.targetRef();
                taskRef = interaction.taskRef();
                payload = new CommandPayload.InteractionRespond(respond.payload());
            }
            default -> throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CAPABILITY,
                "operation is not executed through the backend: " + operation.type());
        }

        if(shared) {
            if(groupGrant==null)groupGrant=groupGrants.forTarget(scopeRef,targetRef);
            groupGrants.requireTarget(groupGrant,scopeRef,targetRef);
            if(operation instanceof ChannelOperation.InteractionRespond respond
                && (respond.payload().response() instanceof InteractionResponse.Approve
                    || respond.payload().response() instanceof InteractionResponse.Reject)
                && !ownerWeixinBindings.matches(entry,groupGrant.ownerRef(),admission.identitySubjectKey()))
                throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Only the bound workspace owner may approve in a group");
        }

        String operationRef = Ids.next("op");
        String packetId = Ids.next("cmd");
        PacketContext context = new PacketContext(tenant, workspace, targetRef, operationRef, taskRef);
        Correlation correlation = causationId == null ? Correlation.of(flowId)
            : new Correlation(flowId, causationId);
        CommandPacket packet = new CommandPacket("1", packetId, type, clock.instant(), correlation, context,
            new Producer(Layer.L2, settings.componentRef()), Actor.of(shared?groupGrant.ownerRef():admission.callerRef()), payload);

        if (isWrite(type)) {
            acceptForDispatch(entry, admission, request, packet, flowId, scopeRef, replyTarget, selectionRevision,groupGrant);
            return new ChannelResponse.Accepted(request.requestId(), operationRef, null);
        }
        return queryNow(entry, admission, request, packet, flowId,groupGrant);
    }

    private boolean isWrite(CommandType type) {
        return type == CommandType.INPUT_SUBMIT || type == CommandType.SESSION_RESUME || type == CommandType.TASK_CANCEL
            || type == CommandType.INTERACTION_RESPOND;
    }

    private String groupAction(CommandPacket packet) {
        if(packet.payload() instanceof CommandPayload.InteractionRespond respond) {
            if(respond.payload().response() instanceof InteractionResponse.Approve)return "APPROVE";
            if(respond.payload().response() instanceof InteractionResponse.Reject)return "REJECT";
            return "ANSWER";
        }
        return packet.type().name();
    }

    private void acceptForDispatch(EntryConfig entry, DirectoryRepository.AdmissionRow admission,
                                   IngressRequest request, CommandPacket packet, String flowId,
                                   String scopeRef, String replyTarget, Long selectionRevision,
                                   GroupWorkspaceGrantService.Grant groupGrant) {
        String operationRef = packet.context().operationRef();
        String dedupeKey = dedupeKey(entry, admission, request.requestId());
        String digest = Digests.sha256(Json.write(PacketJson.enc(request)));
        db.tx(connection -> {
            // 受理事务内再次复验原绑定：与重绑屏障竞争时只有一个可核验先后顺序（SM07 §9.3）。
            requireBindingCurrent(entry, admission);
            if(groupGrant!=null)groupGrants.requireTarget(groupGrant,scopeRef,packet.context().targetRef());
            if (packet.type()==CommandType.INPUT_SUBMIT && request.operation() instanceof ChannelOperation.InputSubmit input) {
                var binding=directory.binding(entry.tenantRef(),entry.workspaceRef(),scopeRef);
                // Explicit legacy writes remain supported only before a scope uses navigation.
                // Normal adapters always provide the revision. Resolution and acceptance compete with CAS.
                if(selectionRevision!=null) {
                    if(binding==null || binding.revision()!=selectionRevision
                        || !java.util.Objects.equals(binding.targetRef(),packet.context().targetRef())) throw NavigationService.conflict();
                } else if(input.targetRef()==null || binding!=null) {
                    if(binding==null || !java.util.Objects.equals(binding.targetRef(),packet.context().targetRef()))throw NavigationService.conflict();
                }
            }
            grants.capture(packet);
            if(groupGrant!=null)groupGrants.recordOperation(operationRef,groupGrant,packet.context().targetRef(),
                admission.identitySubjectKey(),groupAction(packet));
            requests.saveInbox(new RequestRepository.InboxRow(dedupeKey, entry.tenantRef(),
                entry.workspaceRef(), entry.entryRef(), entry.accountBindingEpoch(), admission.callerRef(),
                request.requestId(), digest, packet.packetId(), operationRef, flowId, packet.type().name(),
                Json.write(PacketJson.enc(packet)), RequestRepository.OperationStatus.PENDING_DISPATCH,
                null, 0, null, clock.instant(), admission.identitySubjectKind(),
                admission.identitySubjectKey(), admission.identityBindingRevision()));
            if (packet.payload() instanceof CommandPayload.InputSubmit) {
                DirectoryRepository.DestinationRow destination = requireDestination(entry.tenantRef(),
                    entry.workspaceRef(), replyTarget, entry.entryRef());
                DirectoryRepository.ScopeRow scope = scopeRef == null ? null
                    : directory.scope(entry.tenantRef(), entry.workspaceRef(), scopeRef);
                requests.saveReplyRelation(new RequestRepository.ReplyRelation(Ids.next("rel"), flowId,
                    operationRef, entry.tenantRef(), entry.workspaceRef(), entry.entryRef(),
                    destination.destinationRef(), destination.destinationRevision(), admission.callerRef(),
                    scopeRef, scope != null && scope.shared(), "INPUT_SUBMIT", "ACTIVE"));
            }
            if (packet.payload() instanceof CommandPayload.InteractionRespond respond) {
                // 领取事项与受理写入同一个本地事务：避免“已领取但未受理”的悬挂状态（INV-59、INV-62）。
                if (!requests.claimInteraction(respond.payload().interactionRef(),
                    respond.payload().expectedRevision())) {
                    throw new ChannelContractException(ChannelErrorCode.INTERACTION_STALE,
                        "interaction is already being handled or has been resolved");
                }
                requests.closeActions(respond.payload().interactionRef());
                requests.saveResponse(new RequestRepository.ResponseRow(Ids.next("resp"),
                    respond.payload().interactionRef(), operationRef, admission.callerRef(),
                    respond.payload().expectedRevision(),
                    Json.write(ContentJson.encode(respond.payload().response())), "SUBMITTING"));
            }
            return null;
        });
        dispatcher.schedule(operationRef);
    }

    private ChannelResponse queryNow(EntryConfig entry, DirectoryRepository.AdmissionRow admission,
                                     IngressRequest request, CommandPacket packet, String flowId,
                                     GroupWorkspaceGrantService.Grant groupGrant) {
        String operationRef = packet.context().operationRef();
        String dedupeKey = dedupeKey(entry, admission, request.requestId());
        String digest = Digests.sha256(Json.write(PacketJson.enc(request)));
        db.tx(connection -> {
        requireBindingCurrent(entry, admission);
        if(groupGrant!=null)groupGrants.requireTarget(groupGrant,admission.scopeRef(),packet.context().targetRef());
        grants.capture(packet);
        if(groupGrant!=null)groupGrants.recordOperation(operationRef,groupGrant,packet.context().targetRef(),
            admission.identitySubjectKey(),groupAction(packet));
        requests.saveInbox(new RequestRepository.InboxRow(dedupeKey, entry.tenantRef(), entry.workspaceRef(),
            entry.entryRef(), entry.accountBindingEpoch(), admission.callerRef(), request.requestId(), digest,
            packet.packetId(), operationRef, flowId, packet.type().name(), Json.write(PacketJson.enc(packet)),
            RequestRepository.OperationStatus.PENDING_DISPATCH, null, 1, null, clock.instant(),
            admission.identitySubjectKind(), admission.identitySubjectKey(),
            admission.identityBindingRevision()));
        return null;
        });
        return dispatcher.dispatchQuery(entry, admission.callerRef(), packet, request.requestId());
    }

    private String boundTarget(String tenant, String workspace, String scopeRef) {
        if (scopeRef == null) {
            return null;
        }
        DirectoryRepository.BindingRow binding = directory.binding(tenant, workspace, scopeRef);
        return binding == null ? null : binding.targetRef();
    }

    private DirectoryRepository.DestinationRow requireDestination(String tenant, String workspace,
                                                                  String destinationRef, String entryRef) {
        if (destinationRef == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "no reply destination for this request");
        }
        DirectoryRepository.DestinationRow destination = directory.destination(destinationRef);
        if (destination == null || !destination.tenantRef().equals(tenant)
            || !destination.workspaceRef().equals(workspace)) {
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,
                "reply destination is not available");
        }
        if (!destination.entryRef().equals(entryRef)) {
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,
                "reply destination belongs to another entry");
        }
        return destination;
    }

    private RequestRepository.TaskRow requireTask(String tenant, String workspace, String taskRef) {
        RequestRepository.TaskRow task = requests.task(taskRef);
        if (task == null || !task.tenantRef().equals(tenant) || !task.workspaceRef().equals(workspace)) {
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED, "unknown task");
        }
        return task;
    }

    private RequestRepository.InteractionRow requireInteraction(String tenant, String workspace,
                                                                ChannelOperation.InteractionRespond respond) {
        RequestRepository.InteractionRow interaction = requests.interaction(
            respond.payload().interactionRef());
        if (interaction == null || !interaction.tenantRef().equals(tenant)
            || !interaction.workspaceRef().equals(workspace)) {
            throw new ChannelContractException(ChannelErrorCode.INTERACTION_STALE, "unknown interaction");
        }
        if (!"PENDING".equals(interaction.status())) {
            throw new ChannelContractException(ChannelErrorCode.INTERACTION_STALE,
                "interaction is already being handled or has been resolved");
        }
        return interaction;
    }

    private void validateReferences(ChannelMessage message) {
        for (ContentPart part : message.content()) {
            if (part instanceof ContentPart.MessageReference reference
                && requests.message(reference.messageRef()) == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                    "referenced message is not available: " + reference.messageRef());
            }
        }
        if (message.replyToMessageRef() != null && requests.message(message.replyToMessageRef()) == null) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "replyToMessageRef is not available");
        }
    }

    /** 整组答案与提示的一致性校验（INV-59、INV-60）。 */
    private void validateInteractionResponse(RequestRepository.InteractionRow interaction,
                                             InteractionRespondPayload payload) {
        InteractionPrompt prompt = ContentJson.decodePrompt(Json.read(interaction.promptJson()));
        InteractionResponse response = payload.response();
        String kind = response.kind();
        boolean allowed = prompt.allowedResponses().stream()
            .anyMatch(value -> value.wire().equals(kind));
        if (!allowed) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "response kind is not allowed for this interaction: " + kind);
        }
        switch (response) {
            case InteractionResponse.Approve approve -> {
                if (approve.modifiedArguments() != null
                    && (!(prompt instanceof InteractionPrompt.Approval approval)
                    || approval.operation() == null || !approval.operation().editable())) {
                    throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                        "this approval does not accept modified arguments");
                }
            }
            case InteractionResponse.Reject ignored -> {
                // 拒绝可不带原因；资格仍按事项与实际响应人校验（INV-05）。
            }
            case InteractionResponse.Answer answer -> {
                if (!(prompt instanceof InteractionPrompt.Questions questions)) {
                    throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                        "this interaction does not accept answers");
                }
                Set<String> answered = new HashSet<>();
                for (QuestionAnswer item : answer.answers()) {
                    QuestionItem question = questions.questions().stream()
                        .filter(candidate -> candidate.questionId().equals(item.questionId()))
                        .findFirst()
                        .orElseThrow(() -> new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                            "unknown questionId: " + item.questionId()));
                    answered.add(question.questionId());
                    validateAnswer(question, item);
                }
                for (QuestionItem question : questions.questions()) {
                    if (question.required() && !answered.contains(question.questionId())) {
                        throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                            "required question is unanswered: " + question.questionId());
                    }
                }
            }
        }
    }

    private void validateAnswer(QuestionItem question, QuestionAnswer answer) {
        switch (answer) {
            case QuestionAnswer.TextAnswer ignored -> {
                if (question.input().choice()) {
                    throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                        "text answer is not accepted for a choice question");
                }
            }
            case QuestionAnswer.ChoiceAnswer choice -> {
                if (!(question.input() instanceof QuestionItem.Input.ChoiceInput input)) {
                    throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                        "choice answer is not accepted for a text question");
                }
                if (choice.otherText() != null && !input.allowOther()) {
                    throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                        "otherText is not allowed for this question");
                }
                Set<String> optionIds = new HashSet<>();
                for (QuestionItem.ChoiceOption option : input.options()) {
                    optionIds.add(option.optionId());
                }
                for (String optionId : choice.optionIds()) {
                    if (!optionIds.contains(optionId)) {
                        throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                            "unknown optionId: " + optionId);
                    }
                }
                if (!input.multiple()) {
                    int selected = choice.optionIds().size() + (choice.otherText() == null ? 0 : 1);
                    if (selected != 1) {
                        throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                            "single-choice question accepts exactly one selection");
                    }
                }
            }
        }
    }

    /**
     * 会话列表由 L2 的已同步目标视图回答：保证 /list 展示的顺序与 /use 序号解析使用同一来源，
     * 避免用户按列表选择却落到另一个目标（INV-15）。所有页面按当前主体的有效 target.read 权限过滤。
     */
    private OperationResult sessionListResult(ChannelOperation.SessionList list, EntryConfig entry, String scope, String caller,
            List<com.netlivecowork.channel.contract.SessionSummary> metadata) {
        var rows=navigation.authorized(entry,caller,metadata);
        String filter=list.sessionWorkspaceRef();
        if(filter==null) {
            var selected=directory.binding(entry.tenantRef(),entry.workspaceRef(),scope);
            filter=selected==null?null:selected.sessionWorkspaceRef();
        }
        final String workspaceFilter=filter;
        rows=rows.stream().filter(s->(workspaceFilter==null || s.workspace()!=null && workspaceFilter.equals(s.workspace().sessionWorkspaceRef()))
            && (list.agentRef()==null || list.agentRef().equals(s.agentRef()))).toList();
        String signature=Digests.sha256(String.valueOf(scope)+"|"+filter+"|"+list.agentRef()+"|"+rows.stream().map(s->s.targetRef()).toList());
        int offset=0;
        if(list.page().cursor()!=null) {
            String[] parts=list.page().cursor().split(":",2);
            if(parts.length!=2 || !signature.equals(parts[0]))throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,"Session listing changed; reload the first page");
            offset=cursorOffset(parts[1]);
        }
        int limit=list.page().effectiveLimit();
        var items=rows.subList(Math.min(offset,rows.size()),Math.min(rows.size(),offset+limit));
        navigation.remember(scope,"session",items.stream().map(s->s.targetRef()).toList());
        return new OperationResult.SessionList(items,offset+limit<rows.size()?signature+":"+(offset+limit):null);
    }

    private int cursorOffset(String cursor) {
        if (cursor == null) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(cursor);
            if (offset < 0) {
                throw new NumberFormatException("negative");
            }
            return offset;
        } catch (NumberFormatException e) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,
                "invalid page cursor for this listing");
        }
    }
}
