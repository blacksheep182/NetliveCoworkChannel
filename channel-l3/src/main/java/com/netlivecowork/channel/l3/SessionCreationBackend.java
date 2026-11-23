package com.netlivecowork.channel.l3;

import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.infra.Db;
import com.netlivecowork.channel.infra.Digests;
import java.io.IOException;
import java.time.Clock;
import java.util.*;

/** Pre-resource operations. Never guess a backend from a directory or current selection. */
final class SessionCreationBackend {
    private final Db db; private final Clock clock; private final BackendTransport client;
    private final BackendIdentityResolver identities; private final BackendRegistry registry;
    private final BackendProtocol protocol;private final String sink;
    SessionCreationBackend(Db db,Clock clock,BackendTransport client,BackendIdentityResolver identities,
                           BackendRegistry registry,BackendProtocol protocol,String sink) {
        this.db=db;this.clock=clock;this.client=client;this.identities=identities;this.registry=registry;this.protocol=protocol;this.sink=sink;
        db.ddl("CREATE TABLE IF NOT EXISTS backend_creation_events(request_key TEXT PRIMARY KEY,service_ref TEXT NOT NULL,target_ref TEXT NOT NULL,operation_ref TEXT NOT NULL,sink_ref TEXT NOT NULL,sink_revision INTEGER NOT NULL,resource_epoch INTEGER NOT NULL)");
        db.ddl("CREATE TABLE IF NOT EXISTS backend_session_creations(request_key TEXT PRIMARY KEY,service_ref TEXT NOT NULL,workspace_id TEXT NOT NULL,workspace_ref TEXT NOT NULL,digest TEXT NOT NULL)");
    }
    private record Workspace(BackendService service,String id,SessionWorkspace summary) {}
    private Map<String,String> headers(BackendService service,TrustedCall call) {
        var identity=identities.catalogIdentity(call,service.serviceRef());
        if(identity==null || identity.validUntil()==null || !identity.validUntil().isAfter(clock.instant())
            || !call.principalRef().equals(identities.principalRef(service.serviceRef(),identity.subjectIssuer(),identity.subject())))
            throw new ChannelContractException(ChannelErrorCode.AUTH_REQUIRED,"Current W3 authentication is required");
        return Map.of("X-Channel-Delegation",identity.protectedProof());
    }
    private List<Workspace> catalog(TrustedCall call) throws IOException,InterruptedException {
        return catalog(call, null);
    }
    private List<Workspace> catalog(TrustedCall call, String serviceRef) throws IOException,InterruptedException {
        var result=new ArrayList<Workspace>();
        for(var service:registry.services(call.tenantRef(),call.workspaceRef())) {
            if (serviceRef != null && !serviceRef.equals(service.serviceRef())) continue;
            if(!service.enabled() || identities.catalogIdentity(call,service.serviceRef())==null)continue;
            if(!protocol.capabilities(service).path("features").path("session_create").asBoolean(false))continue;
            var body=BackendProtocol.successful(client.exchange(service,"GET",BackendProtocol.PREFIX+"/creation-workspaces",null,headers(service,call)));
            Json.onlyFields(body,"backend_id","items");
            if(!protocol.registration(service).backendId().equals(Json.text(body,"backend_id")) || !body.path("items").isArray() || body.path("items").size()>200)
                throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE,"Invalid creation workspace catalog");
            var seen=new HashSet<String>();
            for(var item:body.path("items")) {
                Json.onlyFields(item,"workspace_id","label");String id=Json.text(item,"workspace_id");
                if(!id.matches("ws-[a-f0-9]{32}") || !seen.add(id))throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE,"Invalid workspace identity");
                result.add(new Workspace(service,id,protocol.workspace(service,id,Json.text(item,"label"),true)));
            }
        }
        return List.copyOf(result);
    }
    List<SessionWorkspace> workspaces(TrustedCall call) throws IOException,InterruptedException {
        return catalog(call).stream().map(Workspace::summary).toList();
    }
    List<SessionWorkspace> workspaces(TrustedCall call, String service) throws IOException,InterruptedException {
        return catalog(call, service).stream().map(Workspace::summary).toList();
    }
    SessionCreation.Created create(TrustedCall call,String request,String workspace,String prompt,boolean lookupOnly) throws IOException,InterruptedException {
        String key="create-"+Digests.sha256(String.join("\u0000",call.tenantRef(),call.workspaceRef(),call.principalRef(),request));
        String digest=Digests.sha256(Json.write(Json.obj().put("workspace",workspace).put("prompt",prompt)));
        var original=db.queryOne("SELECT service_ref,workspace_id,workspace_ref,digest FROM backend_session_creations WHERE request_key=?",
            r->List.of(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),key);
        if(original!=null && (!original.get(2).equals(workspace)||!original.get(3).equals(digest)))
            throw new ChannelContractException(ChannelErrorCode.REQUEST_CONFLICT,"Original session creation parameters differ");
        Workspace chosen;
        Map<String,String> delegation;
        Long sinkRevision;
        try {
            chosen=catalog(call).stream().filter(w->workspace.equals(w.summary().sessionWorkspaceRef())).findFirst()
                .orElseThrow(()->new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Workspace is not authorized for session creation"));
            if(original!=null && (!original.get(0).equals(chosen.service().serviceRef()) || !original.get(1).equals(chosen.id())))
                throw new ChannelContractException(ChannelErrorCode.BINDING_CONFLICT,"Original creation backend changed");
            delegation=headers(chosen.service(),call);
            sinkRevision=db.queryOne("SELECT revision FROM event_sink_registrations WHERE sink_ref=? AND service_ref=?",r->r.getLong(1),sink,chosen.service().serviceRef());
            if(sinkRevision==null)throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE,"Creation event sink is not registered");
            if(call.deadline()!=null && !clock.instant().isBefore(call.deadline()))
                throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE,"Creation deadline passed before dispatch");
        } catch(ChannelContractException error) {
            if(original==null && !lookupOnly)throw new SessionCreation.NotStarted(error.code(),error.getMessage());
            throw error;
        } catch(IOException | InterruptedException error) {
            if(original==null && !lookupOnly) {
                if(error instanceof InterruptedException)Thread.currentThread().interrupt();
                throw new SessionCreation.NotStarted(ChannelErrorCode.UNAVAILABLE,"Creation preflight could not reach the host; no creation was dispatched");
            }
            throw error;
        }
        boolean send=false;
        if(original==null) {
            if(lookupOnly)throw new ChannelContractException(ChannelErrorCode.OUTCOME_UNKNOWN,"No backend dispatch is confirmed; do not resend the creation request");
            send=db.update("INSERT OR IGNORE INTO backend_session_creations VALUES(?,?,?,?,?)",key,chosen.service().serviceRef(),chosen.id(),workspace,digest)==1;
        }
        var pinned=db.queryOne("SELECT service_ref,workspace_id,workspace_ref,digest FROM backend_session_creations WHERE request_key=?",r->List.of(r.getString(1),r.getString(2),r.getString(3),r.getString(4)),key);
        if(pinned==null || !pinned.equals(List.of(chosen.service().serviceRef(),chosen.id(),workspace,digest)))
            throw new ChannelContractException(ChannelErrorCode.REQUEST_CONFLICT,"Concurrent creation parameters differ from the original request");
        var body=Json.obj().put("request_key",key).put("workspace_id",chosen.id()).put("user_prompt",prompt);
        var reply=client.exchange(chosen.service(),send?"POST":"GET",BackendProtocol.PREFIX+(send?"/sessions":"/session-creations/"+key),
            send?Json.write(body):null,delegation);
        if(!reply.success()) {
            var error=Json.read(reply.body());
            ChannelErrorCode code=reply.status()==401?ChannelErrorCode.AUTH_REQUIRED:reply.status()==403?ChannelErrorCode.PERMISSION_DENIED:ChannelErrorCode.INVALID_ARGUMENT;
            if("not_started".equals(error.path("creation_outcome").asText()))
                throw new SessionCreation.NotStarted(code,"宿主确认尚未创建会话，请检查登录、工作空间授权和默认模型配置。");
            throw new ChannelContractException(reply.status()==401 || reply.status()==403?code:ChannelErrorCode.OUTCOME_UNKNOWN,
                "创建结果尚未确认；请恢复登录或授权后核对原请求。");
        }
        var result=Json.read(reply.body());Json.onlyFields(result,"backend_id","request_key","session_id","resource_epoch","workspace_id","title","status");
        if(!key.equals(Json.text(result,"request_key")) || !protocol.registration(chosen.service()).backendId().equals(Json.text(result,"backend_id"))
            || !chosen.id().equals(Json.text(result,"workspace_id")) || !"completed".equals(Json.text(result,"status")))
            throw new ChannelContractException(ChannelErrorCode.OUTCOME_UNKNOWN,"Invalid original creation evidence");
        String session=Json.text(result,"session_id"),title=Json.text(result,"title");long epoch=Json.reqLong(result,"resource_epoch");
        var registration=registry.discoveredTarget(call,chosen.service(),session,epoch,title);
        if(!registration.enabled())throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Created resource registration is disabled");
        String target=registration.targetRef();
        var ownership=protocol.facts(chosen.service(),registration);
        if(!call.principalRef().equals(ownership.ownerPrincipalRef()))throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Created session owner does not match the actual caller");
        registry.upsertTarget(registration);
        db.update("INSERT OR IGNORE INTO backend_creation_events VALUES(?,?,?,?,?,?,?)",key,chosen.service().serviceRef(),target,request,sink,sinkRevision,epoch);
        var observed=client.exchange(chosen.service(),"POST",BackendProtocol.PREFIX+"/session-creations/"+key+"/observe",
            Json.write(Json.obj().put("event_sink_ref",sink)),headers(chosen.service(),call));
        if(!observed.success() || !"committed".equals(Json.read(observed.body()).path("status").asText()))
            throw new ChannelContractException(ChannelErrorCode.OUTCOME_UNKNOWN,"Created session observation is not confirmed; query the original request");
        return new SessionCreation.Created(new SessionSummary(target,title,null,true,null,chosen.summary()),ownership);
    }
}
