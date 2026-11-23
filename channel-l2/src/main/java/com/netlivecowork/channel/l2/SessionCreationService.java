package com.netlivecowork.channel.l2;

import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.adapter.EntryConfig;
import com.netlivecowork.channel.contract.json.*;
import com.netlivecowork.channel.infra.*;
import com.netlivecowork.channel.l2.store.DirectoryRepository;
import com.netlivecowork.channel.l2.store.RequestRepository;
import java.util.*;
import java.util.concurrent.*;

/** Durable pre-resource admission. A timeout only authorizes an original-key read. */
final class SessionCreationService {
    private final Db db; private final java.time.Clock clock;private final DirectoryRepository directory; private final BackendDispatcher dispatcher;
    private final GrantService grants;private final RequestRepository requests;private final GroupWorkspaceGrantService groups;
    SessionCreationService(Db db,java.time.Clock clock,DirectoryRepository directory,BackendDispatcher dispatcher,GrantService grants,RequestRepository requests,GroupWorkspaceGrantService groups) {
        this.db=db;this.clock=clock;this.directory=directory;this.dispatcher=dispatcher;this.grants=grants;this.requests=requests;this.groups=groups;
        db.ddl("""
            CREATE TABLE IF NOT EXISTS l2_session_creations(
              request_key TEXT PRIMARY KEY,entry_ref TEXT NOT NULL,epoch INTEGER NOT NULL,caller TEXT NOT NULL,
              request_id TEXT NOT NULL,digest TEXT NOT NULL,scope_ref TEXT NOT NULL,workspace TEXT NOT NULL,
              prompt TEXT NOT NULL,revision INTEGER NOT NULL,subject_kind TEXT NOT NULL,subject_key TEXT NOT NULL,
              identity_revision INTEGER NOT NULL,destination TEXT NOT NULL,destination_revision INTEGER NOT NULL,response TEXT)
            """);
        var columns=db.query("PRAGMA table_info(l2_session_creations)",rs->rs.getString("name"));
        if(!columns.contains("effective_owner"))db.ddl("ALTER TABLE l2_session_creations ADD COLUMN effective_owner TEXT");
        if(!columns.contains("group_grant_ref"))db.ddl("ALTER TABLE l2_session_creations ADD COLUMN group_grant_ref TEXT");
        if (!columns.contains("entry_generation")) db.ddl("ALTER TABLE l2_session_creations ADD COLUMN entry_generation INTEGER");
    }
    String key(EntryConfig entry,String caller,String request) {
        return Digests.sha256(SubmitService.requestKey(entry,caller,request));
    }
    boolean exists(EntryConfig entry,String caller,String request) {
        return row(key(entry,caller,request))!=null;
    }
    private record Row(String key,String caller,String effectiveOwner,String groupGrantRef,String request,String digest,String scope,String workspace,String prompt,long revision,Long entryGeneration,
                       String subjectKind,String subjectKey,long identityRevision,String destination,long destinationRevision,String response) {}
    private Row row(String key) {
        return db.queryOne("SELECT * FROM l2_session_creations WHERE request_key=?", r -> {
            long generation = r.getLong("entry_generation");
            Long entryGeneration = r.wasNull() ? null : generation;
            return new Row(key,r.getString("caller"),r.getString("effective_owner"),r.getString("group_grant_ref"),r.getString("request_id"),
                r.getString("digest"),r.getString("scope_ref"),r.getString("workspace"),r.getString("prompt"),r.getLong("revision"),entryGeneration,
                r.getString("subject_kind"),r.getString("subject_key"),r.getLong("identity_revision"),r.getString("destination"),r.getLong("destination_revision"),r.getString("response"));
        },key);
    }
    private void identity(EntryConfig entry,Row row) {
        var current=directory.identity(entry.entryRef(),entry.accountBindingEpoch(),row.subjectKind(),row.subjectKey());
        if(current==null || !current.callerRef().equals(row.caller()) || current.revision()!=row.identityRevision())
            throw new ChannelContractException(ChannelErrorCode.IDENTITY_UNBOUND,"Original creation identity binding changed");
    }
    ChannelResponse submit(EntryConfig entry,DirectoryRepository.AdmissionRow admission,IngressRequest request,String scope,String replyTarget,
                           ChannelOperation.SessionCreate create,Runnable guard,GroupWorkspaceGrantService.Grant groupGrant) {
        String key=key(entry,admission.callerRef(),request.requestId()),digest=Digests.sha256(Json.write(PacketJson.enc(request)));
        var entryGeneration = row(key) == null ? grants.creationPermission(entry.entryRef(), create.sessionWorkspaceRef(), null) : null;
        boolean send=db.tx(c->{
            guard.run();var prior=row(key);
            if(prior!=null) {
                if(!prior.digest().equals(digest))throw new ChannelContractException(ChannelErrorCode.REQUEST_CONFLICT,"Original request content differs");
                return false;
            }
            if(groupGrant!=null && (!groups.current(groupGrant) || !scope.equals(groupGrant.scopeRef())
                || !create.sessionWorkspaceRef().equals(groupGrant.workspaceRef())))
                throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Group workspace grant changed");
            var selected=directory.binding(entry.tenantRef(),entry.workspaceRef(),scope);
            if((selected==null?0:selected.revision())!=create.expectedBindingRevision())throw NavigationService.conflict();
            var destination=directory.destination(replyTarget);
            String audience=groupGrant==null?"PERSONAL":"SHARED";
            if(destination==null || !entry.entryRef().equals(destination.entryRef()) || !entry.tenantRef().equals(destination.tenantRef()) || !entry.workspaceRef().equals(destination.workspaceRef()) || !audience.equals(destination.audience()))
                throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"The reply destination does not match the session scope");
            return db.update("""
                INSERT OR IGNORE INTO l2_session_creations(request_key,entry_ref,epoch,caller,request_id,digest,
                  scope_ref,workspace,prompt,revision,subject_kind,subject_key,identity_revision,destination,
                  destination_revision,response,effective_owner,group_grant_ref,entry_generation)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,?,?,?)
                """,key,entry.entryRef(),entry.accountBindingEpoch(),admission.callerRef(),request.requestId(),digest,
                scope,create.sessionWorkspaceRef(),create.userPrompt(),create.expectedBindingRevision(),admission.identitySubjectKind(),admission.identitySubjectKey(),admission.identityBindingRevision(),replyTarget,destination.destinationRevision(),
                groupGrant==null?admission.callerRef():groupGrant.ownerRef(),groupGrant==null?null:groupGrant.grantRef(),entryGeneration)==1;
        });
        return run(entry,row(key),!send);
    }
    ChannelResponse lookup(EntryConfig entry,String caller,String request) {
        var row=row(key(entry,caller,request));return row==null?null:run(entry,row,true);
    }
    private ChannelResponse run(EntryConfig entry,Row row,boolean lookupOnly) {
        identity(entry,row);
        var groupGrant=row.groupGrantRef()==null?null:groups.byRef(row.groupGrantRef());
        if(row.groupGrantRef()!=null && !groups.current(groupGrant))
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Group workspace grant was revoked");
        String effectiveOwner=row.effectiveOwner()==null?row.caller():row.effectiveOwner();
        if(row.response()!=null) {
            var result=PacketJson.decChannelResponse(row.response());
            if(result instanceof ChannelResponse.Completed complete && complete.result() instanceof OperationResult.SessionCreate created
                && (!grants.canAct(entry.tenantRef(),entry.workspaceRef(),entry.entryRef(),effectiveOwner,created.session().targetRef(),"target.read")
                    || groupGrant!=null && !groups.current(groupGrant)))
                return new ChannelResponse.Rejected(row.request(),ChannelErrorCode.PERMISSION_DENIED.error("Created session authorization was revoked"));
            return result;
        }
        try {
            grants.withEntryPermissions(entry.entryRef(), true,
                () -> grants.creationPermission(entry.entryRef(), row.workspace(), row.entryGeneration()));
            var created=dispatcher.createSession(entry,effectiveOwner,row.key(),row.workspace(),row.prompt(),lookupOnly);
            var item = created.session();
            var owner = created.ownership();
            if(item.workspace()==null || !row.workspace().equals(item.workspace().sessionWorkspaceRef()) || owner==null
                    || !item.targetRef().equals(owner.targetRef()) || !effectiveOwner.equals(owner.ownerPrincipalRef()))
                throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Created session owner is unverified");
            grants.registerCreatedTarget(entry.entryRef(), item.targetRef(), row.workspace(), row.entryGeneration());
            return grants.withEntryPermissions(entry.entryRef(), true, () -> db.tx(c->{
                identity(entry,row);
                if(groupGrant!=null && !groups.current(groupGrant))throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Group workspace grant changed during creation");
                var prior=row(row.key());if(prior.response()!=null)return PacketJson.decChannelResponse(prior.response());
                directory.upsertTarget(new DirectoryRepository.TargetRow(item.targetRef(),entry.tenantRef(),entry.workspaceRef(),item.agentRef(),item.label(),List.of("text"),TargetSummary.Availability.ONLINE,null,1));
                directory.applyOwnership(item.targetRef(),entry.tenantRef(),entry.workspaceRef(),owner.ownerPrincipalRef(),owner.resourceEpoch(),owner.revision(),owner.validUntil());
                var policy=new PersonalSessionPolicy(db);
                if(row.entryGeneration()!=null || policy.state(entry.tenantRef(),entry.workspaceRef(),effectiveOwner)!=null)
                    policy.addCreated(entry.tenantRef(),entry.workspaceRef(),effectiveOwner,item.targetRef());
                db.update("INSERT INTO l2_navigation_catalog VALUES(?,?,?) ON CONFLICT(target_ref) DO UPDATE SET workspace_ref=excluded.workspace_ref,summary_json=excluded.summary_json",item.targetRef(),row.workspace(),Json.write(PacketJson.enc(item)));
                if(groupGrant!=null)groups.recordOrigin(item.targetRef(),groupGrant,row.subjectKey());
                // Selecting is a separate CAS: a late creation cannot undo another tab's choice.
                directory.compareAndSetSelection(entry.tenantRef(),entry.workspaceRef(),row.scope(),row.workspace(),item.targetRef(),row.revision(),row.caller());
                var selected=directory.binding(entry.tenantRef(),entry.workspaceRef(),row.scope());
                var selection=new NavigationSelection(row.scope(),selected==null?null:selected.sessionWorkspaceRef(),selected==null?null:selected.targetRef(),selected==null?0:selected.revision());
                ChannelResponse response=new ChannelResponse.Completed(row.request(),new OperationResult.SessionCreate(item,selection));
                var packet=new CommandPacket("1","cmd-"+row.key(),CommandType.SESSION_CREATE,clock.instant(),Correlation.of("flow-"+row.key()),
                    new PacketContext(entry.tenantRef(),entry.workspaceRef(),item.targetRef(),row.key(),null),new Producer(Layer.L2,"session-creation"),Actor.of(effectiveOwner),
                    new CommandPayload.SessionCreate(row.workspace(),row.prompt(),row.revision()));
                grants.capture(packet, entry.entryRef());
                if(groupGrant!=null)groups.recordOperation(row.key(),groupGrant,item.targetRef(),row.subjectKey(),"SESSION_CREATE");
                String dedupe=SubmitService.requestKey(entry,row.caller(),row.request());
                requests.saveInbox(new RequestRepository.InboxRow(dedupe,entry.tenantRef(),entry.workspaceRef(),entry.entryRef(),entry.accountBindingEpoch(),row.caller(),row.request(),row.digest(),
                    packet.packetId(),row.key(),packet.correlation().flowId(),CommandType.SESSION_CREATE.name(),Json.write(PacketJson.enc(packet)),RequestRepository.OperationStatus.LOCAL_DONE,
                    Json.write(PacketJson.enc(response)),0,null,packet.createdAt(),row.subjectKind(),row.subjectKey(),row.identityRevision()));
                requests.saveReplyRelation(new RequestRepository.ReplyRelation("reply-"+row.key(),packet.correlation().flowId(),row.key(),entry.tenantRef(),entry.workspaceRef(),entry.entryRef(),
                    row.destination(),row.destinationRevision(),row.caller(),row.scope(),groupGrant!=null,"SESSION_CREATE","ACTIVE"));
                db.update("UPDATE l2_session_creations SET response=? WHERE request_key=?",Json.write(PacketJson.enc(response)),row.key());
                return response;
            }));
        } catch(RuntimeException error) {
            Throwable actual=error;while(actual.getCause()!=null && (actual instanceof CompletionException || actual instanceof ExecutionException || actual instanceof RuntimeException && !(actual instanceof ChannelContractException)))actual=actual.getCause();
            if(actual instanceof SessionCreation.NotStarted failure) {
                var rejected=new ChannelResponse.Rejected(row.request(),failure.toError());
                db.update("UPDATE l2_session_creations SET response=? WHERE request_key=? AND response IS NULL",Json.write(PacketJson.enc(rejected)),row.key());
                return rejected;
            }
            return new ChannelResponse.Unknown(row.request(),row.key(),actual instanceof ChannelContractException failure?failure.toError():
                ChannelErrorCode.OUTCOME_UNKNOWN.error("Query the original session creation request; do not send a new request"));
        }
    }
}
