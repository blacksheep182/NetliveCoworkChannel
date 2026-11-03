package com.netlivecowork.channel.l2;

import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.SessionWorkspace;
import com.netlivecowork.channel.contract.TrustedCall;
import com.netlivecowork.channel.contract.adapter.EntryConfig;
import com.netlivecowork.channel.infra.Db;
import com.netlivecowork.channel.infra.Ids;
import com.netlivecowork.channel.l2.store.DirectoryRepository;
import com.netlivecowork.channel.l2.store.EntryRepository;
import java.time.Clock;
import java.util.List;

/** An owner opens one verified Weixin group scope to one creation workspace. */
final class GroupWorkspaceGrantService {
    record GroupScope(String scopeRef, String entryRef, String groupId) {}
    record Grant(String grantRef, String scopeRef, String entryRef, long entryEpoch,
                 String workspaceRef, String ownerRef, long revision, boolean active) {}
    private final Db db;
    private final Clock clock;
    private final EntryRepository entries;
    private final DirectoryRepository directory;
    private final BackendDispatcher dispatcher;
    private final OwnerWeixinBindingService ownerBindings;

    GroupWorkspaceGrantService(Db db, Clock clock, EntryRepository entries, DirectoryRepository directory,
                               BackendDispatcher dispatcher,OwnerWeixinBindingService ownerBindings) {
        this.db=db;this.clock=clock;this.entries=entries;this.directory=directory;this.dispatcher=dispatcher;
        this.ownerBindings=ownerBindings;
        db.ddl("""
            CREATE TABLE IF NOT EXISTS l2_group_workspace_grants(
              grant_ref TEXT PRIMARY KEY, tenant_ref TEXT NOT NULL, deployment_ref TEXT NOT NULL,
              entry_ref TEXT NOT NULL, entry_epoch INTEGER NOT NULL, scope_ref TEXT NOT NULL,
              session_workspace_ref TEXT NOT NULL, owner_ref TEXT NOT NULL, revision INTEGER NOT NULL,
              state TEXT NOT NULL, created_at TEXT NOT NULL, revoked_at TEXT)
            """, """
            CREATE UNIQUE INDEX IF NOT EXISTS l2_group_workspace_grants_active
              ON l2_group_workspace_grants(tenant_ref,deployment_ref,scope_ref,session_workspace_ref)
              WHERE state='enabled'
            """, """
            CREATE TABLE IF NOT EXISTS l2_group_session_origins(
              target_ref TEXT PRIMARY KEY, grant_ref TEXT NOT NULL, scope_ref TEXT NOT NULL,
              session_workspace_ref TEXT NOT NULL, created_by TEXT NOT NULL, created_at TEXT NOT NULL)
            """, """
            CREATE TABLE IF NOT EXISTS l2_group_operations(
              operation_ref TEXT PRIMARY KEY, grant_ref TEXT NOT NULL, scope_ref TEXT NOT NULL,
              target_ref TEXT, sender_ref TEXT NOT NULL, action TEXT NOT NULL)
            """);
    }

    List<GroupScope> observedGroups(TrustedCall owner) {
        return db.query("""
            SELECT scope_ref,entry_ref,external_key FROM l2_scopes
            WHERE tenant_ref=? AND workspace_ref=? AND namespace='weixin-group'
              AND audience='SHARED' AND shared=1 ORDER BY entry_ref,external_key
            """, rs->new GroupScope(rs.getString(1),rs.getString(2),rs.getString(3)),
            owner.tenantRef(),owner.workspaceRef()).stream().filter(scope->{
                EntryConfig entry=entries.find(owner.tenantRef(),owner.workspaceRef(),scope.entryRef());
                return entry!=null && entry.enabled() && "weixin".equals(entry.adapterType());
            }).toList();
    }

    List<Grant> grants(TrustedCall owner) {
        return db.query("""
            SELECT * FROM l2_group_workspace_grants WHERE tenant_ref=? AND deployment_ref=? AND owner_ref=?
            ORDER BY created_at,grant_ref
            """, this::map, owner.tenantRef(),owner.workspaceRef(),owner.principalRef());
    }

    List<SessionWorkspace> availableWorkspaces(TrustedCall owner, String entryRef) {
        return dispatcher.creationWorkspaces(requireEntry(owner,entryRef),owner.principalRef()).stream()
            .filter(SessionWorkspace::canCreate).toList();
    }

    Grant open(TrustedCall owner, String entryRef, String scopeRef, String workspaceRef) {
        EntryConfig entry=requireEntry(owner,entryRef);
        DirectoryRepository.ScopeRow scope=directory.scope(owner.tenantRef(),owner.workspaceRef(),scopeRef);
        if(scope==null || !scope.shared() || !"SHARED".equals(scope.audience())
            || !"weixin-group".equals(scope.namespace()) || !entryRef.equals(scope.entryRef())
            || scope.accountBindingEpoch()!=entry.accountBindingEpoch()) throw denied();
        // The backend catalog is checked before the transaction; it is rechecked on every use.
        List<SessionWorkspace> workspaces=dispatcher.creationWorkspaces(entry,owner.principalRef());
        if(workspaces.stream().noneMatch(item->item.canCreate()
            && workspaceRef.equals(item.sessionWorkspaceRef()))) throw denied();
        return db.tx(connection->{
            EntryConfig current=requireEntry(owner,entryRef);
            if(current.accountBindingEpoch()!=entry.accountBindingEpoch()
                || !clock.instant().isBefore(owner.deadline())) throw denied();
            DirectoryRepository.ScopeRow currentScope=directory.scope(owner.tenantRef(),owner.workspaceRef(),scopeRef);
            if(currentScope==null || currentScope.accountBindingEpoch()!=current.accountBindingEpoch()
                || !currentScope.shared() || !"weixin-group".equals(currentScope.namespace())) throw denied();
            Grant existing=active(scopeRef,workspaceRef);
            if(existing!=null) {
                if(!owner.principalRef().equals(existing.ownerRef())) throw denied();
                return existing;
            }
            if(activeForScope(scopeRef).stream().anyMatch(item->!owner.principalRef().equals(item.ownerRef())))
                throw denied();
            String ref=Ids.next("group-grant");
            db.update("INSERT INTO l2_group_workspace_grants VALUES(?,?,?,?,?,?,?,?,1,'enabled',?,NULL)",
                ref,owner.tenantRef(),owner.workspaceRef(),entryRef,current.accountBindingEpoch(),scopeRef,
                workspaceRef,owner.principalRef(),clock.instant().toString());
            return active(scopeRef,workspaceRef);
        });
    }

    Grant revoke(TrustedCall owner, String grantRef, long expectedRevision) {
        return db.tx(connection->{
            Grant grant=byRef(grantRef);
            if(grant==null || !owner.tenantRef().equals(grantTenant(grantRef))
                || !owner.workspaceRef().equals(grantDeployment(grantRef))
                || !owner.principalRef().equals(grant.ownerRef())) throw denied();
            if(!grant.active() || grant.revision()!=expectedRevision)
                throw new ChannelContractException(ChannelErrorCode.GRANT_REVISION_CONFLICT,
                    "Group workspace grant changed; reload before revoking");
            db.update("UPDATE l2_group_workspace_grants SET state='revoked',revision=revision+1,revoked_at=? WHERE grant_ref=? AND state='enabled' AND revision=?",
                clock.instant().toString(),grantRef,expectedRevision);
            return byRef(grantRef);
        });
    }

    Grant active(String scopeRef, String workspaceRef) {
        return db.queryOne("""
            SELECT * FROM l2_group_workspace_grants WHERE scope_ref=? AND session_workspace_ref=?
              AND state='enabled'
            """,this::map,scopeRef,workspaceRef);
    }
    List<Grant> activeForScope(String scopeRef) {
        return db.query("SELECT * FROM l2_group_workspace_grants WHERE scope_ref=? AND state='enabled' ORDER BY session_workspace_ref",
            this::map,scopeRef).stream().filter(this::current).toList();
    }

    boolean current(Grant grant) {
        if(grant==null || !grant.active())return false;
        Grant active=active(grant.scopeRef(),grant.workspaceRef());
        if(active==null || !grant.grantRef().equals(active.grantRef())
            || grant.revision()!=active.revision() || !grant.ownerRef().equals(active.ownerRef()))return false;
        EntryConfig entry=entries.find(grant.entryRef());
        DirectoryRepository.ScopeRow scope=directory.scope(grantTenant(grant.grantRef()),
            grantDeployment(grant.grantRef()),grant.scopeRef());
        return entry!=null && entry.enabled() && entry.accountBindingEpoch()==grant.entryEpoch()
            && scope!=null && scope.shared() && "weixin-group".equals(scope.namespace())
            && scope.accountBindingEpoch()==grant.entryEpoch();
    }

    Grant byRef(String ref) {
        return db.queryOne("SELECT * FROM l2_group_workspace_grants WHERE grant_ref=?",this::map,ref);
    }
    void recordOrigin(String targetRef, Grant grant, String sender) {
        if(!current(grant))throw denied();
        db.update("INSERT INTO l2_group_session_origins VALUES(?,?,?,?,?,?)",targetRef,
            grant.grantRef(),grant.scopeRef(),grant.workspaceRef(),sender,clock.instant().toString());
    }
    Grant forTarget(String scopeRef,String targetRef) {
        return db.queryOne("""
            SELECT g.* FROM l2_group_session_origins o JOIN l2_group_workspace_grants g
              ON g.grant_ref=o.grant_ref WHERE o.target_ref=? AND o.scope_ref=? AND g.state='enabled'
            """,this::map,targetRef,scopeRef);
    }
    List<String> targets(String scopeRef) {
        return db.query("""
            SELECT o.target_ref FROM l2_group_session_origins o JOIN l2_group_workspace_grants g
              ON g.grant_ref=o.grant_ref WHERE o.scope_ref=? AND g.state='enabled'
            ORDER BY o.created_at DESC,o.target_ref
            """,rs->rs.getString(1),scopeRef);
    }
    void requireTarget(Grant grant,String scopeRef,String targetRef) {
        if(grant==null || !current(grant) || !grant.scopeRef().equals(scopeRef)
            || targetRef==null || !grant.grantRef().equals(
                db.queryOne("SELECT grant_ref FROM l2_group_session_origins WHERE target_ref=? AND scope_ref=?",
                    rs->rs.getString(1),targetRef,scopeRef))) throw denied();
    }
    void recordOperation(String operationRef,Grant grant,String targetRef,String sender,String action) {
        if(!current(grant))throw denied();
        if(targetRef!=null)requireTarget(grant,grant.scopeRef(),targetRef);
        db.update("INSERT INTO l2_group_operations VALUES(?,?,?,?,?,?)",operationRef,
            grant.grantRef(),grant.scopeRef(),targetRef,sender,action);
    }
    boolean currentOperation(String operationRef) {
        var row=db.queryOne("SELECT grant_ref,scope_ref,target_ref,sender_ref,action FROM l2_group_operations WHERE operation_ref=?",
            rs->new String[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5)},operationRef);
        if(row==null)return true;
        Grant grant=byRef(row[0]);
        if(!current(grant)||!grant.scopeRef().equals(row[1]))return false;
        if(row[2]!=null)try{requireTarget(grant,row[1],row[2]);}catch(ChannelContractException ignored){return false;}
        if("APPROVE".equals(row[4]) || "REJECT".equals(row[4])) {
            EntryConfig entry=entries.find(grant.entryRef());
            if(entry==null || !ownerBindings.matches(entry,grant.ownerRef(),row[3]))return false;
        }
        return true;
    }
    String operationScope(String operationRef) {
        return db.queryOne("SELECT scope_ref FROM l2_group_operations WHERE operation_ref=?",
            rs->rs.getString(1),operationRef);
    }
    boolean currentDelivery(String deliveryId, GrantService ownerGrants) {
        for(String operation:db.query("""
            SELECT rr.operation_ref FROM l2_outbox_relations r JOIN l2_reply_relations rr
              ON rr.relation_id=r.relation_ref WHERE r.delivery_id=? AND r.relation_type='REPLY'
              AND r.active=1 AND rr.shared=1 AND rr.status='ACTIVE'
            """,rs->rs.getString(1),deliveryId)) {
            if(db.queryOne("SELECT operation_ref FROM l2_group_operations WHERE operation_ref=?",
                rs->rs.getString(1),operation)!=null && currentOperation(operation)
                && ownerGrants.current(operation))return true;
        }
        return false;
    }
    private Grant map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Grant(rs.getString("grant_ref"),rs.getString("scope_ref"),rs.getString("entry_ref"),
            rs.getLong("entry_epoch"),rs.getString("session_workspace_ref"),rs.getString("owner_ref"),
            rs.getLong("revision"),"enabled".equals(rs.getString("state")));
    }
    private String grantTenant(String ref) {
        return db.queryOne("SELECT tenant_ref FROM l2_group_workspace_grants WHERE grant_ref=?",rs->rs.getString(1),ref);
    }
    private String grantDeployment(String ref) {
        return db.queryOne("SELECT deployment_ref FROM l2_group_workspace_grants WHERE grant_ref=?",rs->rs.getString(1),ref);
    }
    private EntryConfig requireEntry(TrustedCall owner,String ref) {
        EntryConfig entry=entries.find(owner.tenantRef(),owner.workspaceRef(),ref);
        if(entry==null || !entry.enabled() || !"weixin".equals(entry.adapterType()))throw denied();
        String observed=entries.observedAccount(ref),expected=entries.expectedExternalAccountKey(ref);
        if(observed==null || observed.isBlank() || expected!=null && !expected.equals(observed))throw denied();
        return entry;
    }
    private static ChannelContractException denied() {
        return new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,
            "Group workspace grant is not available for this owner and observed group");
    }
}
