package com.netlivecowork.channel.l2;

import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.adapter.EntryConfig;
import com.netlivecowork.channel.contract.json.*;
import com.netlivecowork.channel.infra.Db;
import com.netlivecowork.channel.l2.store.DirectoryRepository;
import java.util.*;

/** L2 owns selections. Backend reads precede the short CAS/acceptance transaction. */
final class NavigationService {
    private final Db db; private final DirectoryRepository directory;
    private final GrantService grants; private final BackendDispatcher dispatcher;
    NavigationService(Db db, DirectoryRepository directory, GrantService grants, BackendDispatcher dispatcher) {
        this.db=db;this.directory=directory;this.grants=grants;this.dispatcher=dispatcher;
        db.ddl("CREATE TABLE IF NOT EXISTS l2_navigation_catalog(target_ref TEXT PRIMARY KEY, workspace_ref TEXT, summary_json TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS l2_navigation_lists(scope_ref TEXT NOT NULL, kind TEXT NOT NULL, items_json TEXT NOT NULL, PRIMARY KEY(scope_ref,kind))");
    }
    List<SessionSummary> read(EntryConfig entry, String caller, String target) {
        var result=new ArrayList<SessionSummary>();
        for(var row:directory.targets(entry.tenantRef(),entry.workspaceRef())) {
            if (target!=null&&!target.equals(row.targetRef())) continue;
            if (!grants.canAct(entry.tenantRef(),entry.workspaceRef(),caller,row.targetRef(),"target.read")) continue;
            var item=dispatcher.readSession(entry,caller,row.targetRef());
            db.update("INSERT INTO l2_navigation_catalog VALUES(?,?,?) ON CONFLICT(target_ref) DO UPDATE SET workspace_ref=excluded.workspace_ref,summary_json=excluded.summary_json",
                item.targetRef(),item.workspace()==null?null:item.workspace().sessionWorkspaceRef(),Json.write(PacketJson.enc(item)));
            result.add(item);
        }
        return List.copyOf(result);
    }
    List<SessionSummary> authorized(EntryConfig entry,String caller,List<SessionSummary> items) {
        return items.stream().filter(s->grants.canAct(entry.tenantRef(),entry.workspaceRef(),caller,s.targetRef(),"target.read")).toList();
    }
    Set<String> visibleWorkspaces(String tenant,String workspace,String caller) {
        var result=new HashSet<String>();
        for(var target:directory.targets(tenant,workspace)) if(grants.canAct(tenant,workspace,caller,target.targetRef(),"target.read")) {
            String ref=db.queryOne("SELECT workspace_ref FROM l2_navigation_catalog WHERE target_ref=?",r->r.getString(1),target.targetRef());
            if(ref!=null)result.add(ref);
        }
        return result;
    }
    NavigationSelection selection(EntryConfig entry,String scope,String caller,List<SessionSummary> items) {
        var current=directory.binding(entry.tenantRef(),entry.workspaceRef(),scope);
        if(current==null)return new NavigationSelection(scope,null,null,0);
        String workspace=current.sessionWorkspaceRef(),target=current.targetRef();
        if(workspace==null && target!=null) {
            var original=items.stream().filter(s->target.equals(s.targetRef())).findFirst().orElse(null);
            if(original!=null && original.workspace()!=null) {
                directory.compareAndSetSelection(entry.tenantRef(),entry.workspaceRef(),scope,original.workspace().sessionWorkspaceRef(),target,current.revision(),caller);
                return snapshot(entry,scope);
            }
        }
        if(workspace!=null && items.stream().noneMatch(s->s.workspace()!=null&&workspace.equals(s.workspace().sessionWorkspaceRef()))) {
            directory.compareAndSetSelection(entry.tenantRef(),entry.workspaceRef(),scope,null,null,current.revision(),caller);
        } else if(target!=null && items.stream().noneMatch(s->target.equals(s.targetRef()) && (workspace==null || s.workspace()!=null&&workspace.equals(s.workspace().sessionWorkspaceRef())))) {
            directory.compareAndSetSelection(entry.tenantRef(),entry.workspaceRef(),scope,workspace,null,current.revision(),caller);
        }
        return snapshot(entry,scope);
    }
    NavigationSelection snapshot(EntryConfig entry,String scope) {
        var b=directory.binding(entry.tenantRef(),entry.workspaceRef(),scope);
        return new NavigationSelection(scope,b==null?null:b.sessionWorkspaceRef(),b==null?null:b.targetRef(),b==null?0:b.revision());
    }
    /** Reads L2's current selection and live local grants; never enumerates backend sessions. */
    NavigationSelection current(EntryConfig entry,String scope,String caller) {
        var selected=snapshot(entry,scope);
        String workspace=selected.sessionWorkspaceRef(),target=selected.targetRef();
        if(workspace!=null&&!visibleWorkspaces(entry.tenantRef(),entry.workspaceRef(),caller).contains(workspace)) {
            workspace=null;target=null;
        } else if(target!=null&&!grants.canAct(entry.tenantRef(),entry.workspaceRef(),caller,target,"target.read")) {
            target=null;
        }
        if(!Objects.equals(workspace,selected.sessionWorkspaceRef())||!Objects.equals(target,selected.targetRef()))
            directory.compareAndSetSelection(entry.tenantRef(),entry.workspaceRef(),scope,workspace,target,selected.revision(),caller);
        return snapshot(entry,scope);
    }
    OperationResult.WorkspaceList list(EntryConfig entry,String scope,String caller,List<SessionSummary> input) {
        var items=authorized(entry,caller,input);
        if(items.stream().anyMatch(s->s.workspace()==null))throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CAPABILITY,"Backend does not expose verified session workspaces");
        var workspaces=items.stream().map(SessionSummary::workspace).distinct().sorted(Comparator.comparing(SessionWorkspace::label).thenComparing(SessionWorkspace::sessionWorkspaceRef)).toList();
        remember(scope,"workspace",workspaces.stream().map(SessionWorkspace::sessionWorkspaceRef).toList());
        return new OperationResult.WorkspaceList(workspaces,selection(entry,scope,caller,items));
    }
    OperationResult.WorkspaceSelect select(EntryConfig entry,String scope,String caller,ChannelOperation.WorkspaceSelect op,List<SessionSummary> items) {
        if(authorized(entry,caller,items).stream().noneMatch(s->s.workspace()!=null&&op.sessionWorkspaceRef().equals(s.workspace().sessionWorkspaceRef())))
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Workspace has no authorized session");
        var current=snapshot(entry,scope);
        if(current.revision()!=op.expectedBindingRevision())throw conflict();
        if(!op.sessionWorkspaceRef().equals(current.sessionWorkspaceRef())) {
            if(!directory.compareAndSetSelection(entry.tenantRef(),entry.workspaceRef(),scope,op.sessionWorkspaceRef(),null,op.expectedBindingRevision(),caller))throw conflict();
        }
        return new OperationResult.WorkspaceSelect(snapshot(entry,scope));
    }
    void remember(String scope,String kind,List<String> refs) {
        if(scope!=null)db.update("INSERT INTO l2_navigation_lists VALUES(?,?,?) ON CONFLICT(scope_ref,kind) DO UPDATE SET items_json=excluded.items_json",scope,kind,Json.write(Json.mapper().valueToTree(refs)));
    }
    static ChannelContractException conflict() {return new ChannelContractException(ChannelErrorCode.BINDING_CONFLICT,"Selection changed; query current selection and choose again");}
}
