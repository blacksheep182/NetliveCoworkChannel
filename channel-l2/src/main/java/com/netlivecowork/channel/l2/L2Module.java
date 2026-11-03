package com.netlivecowork.channel.l2;

import com.netlivecowork.channel.contract.adapter.EntryConfig;
import com.netlivecowork.channel.contract.adapter.StopReason;
import com.netlivecowork.channel.contract.port.AdapterCatalog;
import com.netlivecowork.channel.contract.port.ApplicationIntakePort;
import com.netlivecowork.channel.contract.port.BackendAccessPort;
import com.netlivecowork.channel.contract.port.IngressPort;
import com.netlivecowork.channel.infra.Db;
import com.netlivecowork.channel.l2.store.DeliveryRepository;
import com.netlivecowork.channel.l2.store.DirectoryRepository;
import com.netlivecowork.channel.l2.store.EntryRepository;
import com.netlivecowork.channel.l2.store.EventRepository;
import com.netlivecowork.channel.l2.store.L2Schema;
import com.netlivecowork.channel.l2.store.RequestRepository;
import java.time.Clock;
import java.time.Duration;

/**
 * L2 装配入口：仓储、受理、派发、事件接管、投递与连接调度。框架无关，可由任意宿主周期调用 tick()。
 */
public final class L2Module implements AutoCloseable {

    private final com.netlivecowork.channel.contract.port.BackendAccessPort backendAccess;
    private final UserSessionAccessService sessionAccess;
    private final OwnerWeixinBindingService ownerWeixinBindings;
    private final GroupWorkspaceGrantService groupWorkspaceGrants;
    private final Clock managementClock;
    private final L2Settings settings;
    private final Clock clock;
    private final Db db;
    private final EntryRepository entries;
    private final DirectoryRepository directory;
    private final RequestRepository requests;
    private final DeliveryRepository deliveries;
    private final EventRepository events;
    private final ConnectionManager connections;
    private final HostFacilities hosts;
    private final BackendDispatcher dispatcher;
    private final OutboxService outbox;
    private final EventIntakeService intake;
    private final com.netlivecowork.channel.l2.store.RegistrationRepository registrations;
    private final BackendRegistrationPump registrationPump;
    private final OwnershipRefreshPump ownershipRefresh;
    private final ManagementService management;
    private final SubmitService submit;
    private final GrantService grants;
    private final DurableInboxService durableInbox;

    public L2Module(Db db, Clock clock, L2Settings settings, AdapterCatalog catalog, BackendAccessPort backend,
                    SecretBox secretBox) {
        this.backendAccess=backend;
        this.settings = settings;
        this.clock = clock;
        this.db = db;
        L2Schema.apply(db);
        this.entries = new EntryRepository(db, clock);
        this.directory = new DirectoryRepository(db, clock);
        this.ownerWeixinBindings = new OwnerWeixinBindingService(db, clock, entries, directory);
        this.registrations = new com.netlivecowork.channel.l2.store.RegistrationRepository(db, clock);
        this.requests = new RequestRepository(db, clock);
        this.deliveries = new DeliveryRepository(db, clock);
        this.events = new EventRepository(db, clock);
        this.grants = new GrantService(db, clock);
        this.dispatcher = new BackendDispatcher(settings, clock, entries, directory, requests, backend, grants);
        this.groupWorkspaceGrants = new GroupWorkspaceGrantService(db, clock, entries, directory, dispatcher,ownerWeixinBindings);
        this.dispatcher.bindGroupGrants(groupWorkspaceGrants);
        CommandInterpreter interpreter = new CommandInterpreter(directory, requests, grants);
        this.submit = new SubmitService(db, clock, settings, directory, requests, deliveries, interpreter,
            dispatcher, grants, catalog, groupWorkspaceGrants, ownerWeixinBindings);
        AdmissionService admission = new AdmissionService(clock, settings, entries, directory, requests, submit);

        HostFacilities[] hostHolder = new HostFacilities[1];
        ConnectionManager[] managerHolder = new ConnectionManager[1];
        this.connections = new ConnectionManager(clock, entries, catalog,
            entry -> hostHolder[0].hostFor(entry, adapterVersion(catalog, entry)));
        managerHolder[0] = connections;
        InboundDispositionFactory dispositionFactory =
            (entryRef, epoch) -> new com.netlivecowork.channel.l2.store.InboundDispositionStore(db, clock,
                entryRef, epoch);
        this.managementClock=clock;
        this.sessionAccess=new UserSessionAccessService(db,clock,directory,backend,grants);
        this.durableInbox=new DurableInboxService(db,clock,deliveries,directory,grants);
        this.hosts = new HostFacilities(clock, admission,
            (entryRef, event) -> managerHolder[0].handleEvent(entryRef, event), entries, directory, deliveries,
            backend, secretBox, dispositionFactory, sessionAccess,
            new SessionHistoryService(db,clock,catalog,backend,grants,requests),
            durableInbox, ownerWeixinBindings);
        hostHolder[0] = hosts;
        this.outbox = new OutboxService(settings, clock, entries, directory, deliveries, connections, grants);
        this.outbox.bindGroupGrants(groupWorkspaceGrants);
        this.outbox.bindInbox(durableInbox);
        this.intake = new EventIntakeService(db, clock, settings, directory, requests, events, deliveries,
            backend, registrations);
        this.intake.bindCatalog(catalog);
        this.dispatcher.bindInteractionIntake(this.intake);
        this.registrationPump = new BackendRegistrationPump(registrations, backend, clock);
        this.management = new ManagementService(db, clock, settings, entries, directory, deliveries, catalog,
            connections, backend, hosts);
        this.ownershipRefresh = new OwnershipRefreshPump(directory, management, settings, clock);
    }

    private static String adapterVersion(AdapterCatalog catalog, EntryConfig entry) {
        var descriptor = catalog.descriptor(entry.adapterType());
        return descriptor == null ? "unknown" : descriptor.implementationVersion();
    }

    /** Invoked only by the authenticated Channel administration entry, never by a platform user. */
    public com.netlivecowork.channel.contract.DeviceAccess.Ticket issueDeviceEnrollment(String requestId,String serviceRef,String deviceRef){
        if(requestId==null||!requestId.matches("[A-Za-z0-9._~-]{1,128}")||serviceRef==null||deviceRef==null)
            throw new com.netlivecowork.channel.contract.ChannelContractException(com.netlivecowork.channel.contract.ChannelErrorCode.INVALID_ARGUMENT,"A fixed enrollment request, service and device are required");
        db.ddl("CREATE TABLE IF NOT EXISTS device_registration_authorizations(request_id TEXT PRIMARY KEY,service_ref TEXT NOT NULL,device_ref TEXT NOT NULL,created_at TEXT NOT NULL)");
        db.tx(c->{
            var prior=db.queryOne("SELECT service_ref,device_ref FROM device_registration_authorizations WHERE request_id=?",rs->java.util.List.of(rs.getString(1),rs.getString(2)),requestId);
            if(prior!=null&&(!prior.get(0).equals(serviceRef)||!prior.get(1).equals(deviceRef)))
                throw new com.netlivecowork.channel.contract.ChannelContractException(com.netlivecowork.channel.contract.ChannelErrorCode.REQUEST_CONFLICT,"Enrollment authorization already has different parameters");
            db.update("INSERT OR IGNORE INTO device_registration_authorizations VALUES(?,?,?,?)",requestId,serviceRef,deviceRef,clock.instant());return null;
        });
        return backendAccess.issueDeviceEnrollment(new com.netlivecowork.channel.contract.DeviceAccess.Authorization(
            settings.tenantRef(),settings.workspaceRef(),requestId,serviceRef,deviceRef)).toCompletableFuture().join();
    }

    /** Administrative entry only; device revocation is separate from user/session grants. */
    public void revokeDevice(String deviceRef){
        backendAccess.revokeDevice(deviceRef,new com.netlivecowork.channel.contract.TrustedCall(
            settings.tenantRef(),settings.workspaceRef(),"channel-administration","channel-administrator",null,0,
            clock.instant().plusSeconds(20))).toCompletableFuture().join();
    }
    public EventIntakeService intake() {
        return intake;
    }

    public ManagementService management() {
        return management;
    }

    private void requireManager(com.netlivecowork.channel.contract.SessionAccess.Manager manager) {
        if(manager==null || manager.principalRef()==null || manager.authenticationRef()==null || manager.validUntil()==null || !manager.validUntil().isAfter(managementClock.instant()))
            throw new com.netlivecowork.channel.contract.ChannelContractException(com.netlivecowork.channel.contract.ChannelErrorCode.AUTH_REQUIRED,"Current authenticated CoWork context is required");
    }
    private com.netlivecowork.channel.contract.TrustedCall managerCall(com.netlivecowork.channel.contract.SessionAccess.Manager m) {
        requireManager(m);return new com.netlivecowork.channel.contract.TrustedCall(settings.tenantRef(),settings.workspaceRef(),"cowork-management",m.principalRef(),m.authenticationRef(),0,m.validUntil());
    }
    public com.netlivecowork.channel.contract.SessionAccess.View getSessionAccess(com.netlivecowork.channel.contract.SessionAccess.Manager m) {return sessionAccess.get(managerCall(m),()->requireManager(m));}
    public com.netlivecowork.channel.contract.SessionAccess.Saved saveSessionAccess(com.netlivecowork.channel.contract.SessionAccess.Manager m,com.netlivecowork.channel.contract.SessionAccess.Save request) {return sessionAccess.save(managerCall(m),request,()->requireManager(m));}
    public com.netlivecowork.channel.contract.SessionAccess.Saved lookupSessionAccess(com.netlivecowork.channel.contract.SessionAccess.Manager m,String requestId) {return sessionAccess.lookup(managerCall(m),requestId,()->requireManager(m));}

    public record WeixinChallenge(String token, java.time.Instant expiresAt) {}
    public record WeixinBindingStatus(boolean bound, String weixinSubject) {}
    public record WeixinBindingEntry(String entryRef, String name, boolean accountVerified, boolean bound) {}
    public java.util.List<WeixinBindingEntry> ownerWeixinBindingEntries(com.netlivecowork.channel.contract.SessionAccess.Manager m) {
        return ownerWeixinBindings.list(managerCall(m)).stream()
            .map(item -> new WeixinBindingEntry(item.entryRef(), item.name(), item.accountVerified(), item.bound()))
            .toList();
    }
    public WeixinChallenge beginOwnerWeixinBinding(com.netlivecowork.channel.contract.SessionAccess.Manager m, String entryRef) {
        var result = ownerWeixinBindings.begin(managerCall(m), entryRef);
        return new WeixinChallenge(result.token(), result.expiresAt());
    }
    public WeixinBindingStatus ownerWeixinBindingStatus(com.netlivecowork.channel.contract.SessionAccess.Manager m, String entryRef) {
        var result = ownerWeixinBindings.status(managerCall(m), entryRef);
        return new WeixinBindingStatus(result.bound(), result.weixinSubject());
    }
    public void revokeOwnerWeixinBinding(com.netlivecowork.channel.contract.SessionAccess.Manager m, String entryRef) {
        ownerWeixinBindings.revoke(managerCall(m), entryRef);
    }

    public record QqBindingEntry(String entryRef,String name,String scopeRef,String groupId,
                                 boolean accountVerified,boolean bound) {}
    public java.util.List<QqBindingEntry> ownerQqBindingEntries(com.netlivecowork.channel.contract.SessionAccess.Manager m) {
        return ownerWeixinBindings.qqEntries(managerCall(m)).stream()
            .map(e->new QqBindingEntry(e.entryRef(),e.name(),e.scopeRef(),e.groupId(),e.accountVerified(),e.bound()))
            .toList();
    }
    public WeixinChallenge beginOwnerQqBinding(com.netlivecowork.channel.contract.SessionAccess.Manager m,
                                                String entryRef,String scopeRef) {
        var challenge=ownerWeixinBindings.beginQq(managerCall(m),entryRef,scopeRef);
        return new WeixinChallenge(challenge.token(),challenge.expiresAt());
    }
    public WeixinBindingStatus ownerQqBindingStatus(com.netlivecowork.channel.contract.SessionAccess.Manager m,
                                                   String entryRef,String scopeRef) {
        var status=ownerWeixinBindings.qqStatus(managerCall(m),entryRef,scopeRef);
        return new WeixinBindingStatus(status.bound(),status.weixinSubject());
    }
    public void revokeOwnerQqBinding(com.netlivecowork.channel.contract.SessionAccess.Manager m,
                                     String entryRef,String scopeRef) {
        ownerWeixinBindings.revokeQq(managerCall(m),entryRef,scopeRef);
    }

    public record ObservedGroup(String scopeRef, String entryRef, String groupId) {}
    public record GroupWorkspaceGrant(String grantRef, String scopeRef, String entryRef,
                                      String sessionWorkspaceRef, String ownerRef, long revision, boolean active) {}
    public java.util.List<ObservedGroup> observedGroups(com.netlivecowork.channel.contract.SessionAccess.Manager m) {
        return groupWorkspaceGrants.observedGroups(managerCall(m)).stream()
            .map(group -> new ObservedGroup(group.scopeRef(), group.entryRef(), group.groupId())).toList();
    }
    public java.util.List<ObservedGroup> observedWeixinGroups(com.netlivecowork.channel.contract.SessionAccess.Manager m) {
        return observedGroups(m);
    }
    public java.util.List<GroupWorkspaceGrant> groupWorkspaceGrants(com.netlivecowork.channel.contract.SessionAccess.Manager m) {
        return groupWorkspaceGrants.grants(managerCall(m)).stream().map(this::grantView).toList();
    }
    public java.util.List<com.netlivecowork.channel.contract.SessionWorkspace> groupCreationWorkspaces(
        com.netlivecowork.channel.contract.SessionAccess.Manager m,String entryRef) {
        return groupWorkspaceGrants.availableWorkspaces(managerCall(m),entryRef);
    }
    public GroupWorkspaceGrant openGroupWorkspace(com.netlivecowork.channel.contract.SessionAccess.Manager m,
                                                   String entryRef, String scopeRef, String sessionWorkspaceRef) {
        return grantView(groupWorkspaceGrants.open(managerCall(m),entryRef,scopeRef,sessionWorkspaceRef));
    }
    public GroupWorkspaceGrant revokeGroupWorkspace(com.netlivecowork.channel.contract.SessionAccess.Manager m,
                                                     String grantRef, long expectedRevision) {
        return grantView(groupWorkspaceGrants.revoke(managerCall(m),grantRef,expectedRevision));
    }
    private GroupWorkspaceGrant grantView(GroupWorkspaceGrantService.Grant grant) {
        return new GroupWorkspaceGrant(grant.grantRef(),grant.scopeRef(),grant.entryRef(),
            grant.workspaceRef(),grant.ownerRef(),grant.revision(),grant.active());
    }

    public com.netlivecowork.channel.contract.grant.GrantManagement.GrantManagementPort grantManagement() {
        return grants;
    }

    GrantService grants() {
        return grants;
    }
    GroupWorkspaceGrantService groupGrants() { return groupWorkspaceGrants; }

    /** 管理展示投影：最近 N 条入站处置，不作为保留或去重依据（S70）。 */
    public java.util.List<com.netlivecowork.channel.contract.InboundDisposition> inboundDispositions(
        String entryRef, int limit) {
        EntryConfig entry = entries.find(entryRef);
        if (entry == null) {
            throw new IllegalArgumentException("unknown entry: " + entryRef);
        }
        return com.netlivecowork.channel.l2.store.InboundDispositionStore.recent(db, entryRef,
            entry.accountBindingEpoch(), limit);
    }

    public ConnectionManager connections() {
        return connections;
    }

    // ---- 模块内部视图：仅同包测试与内部服务使用，装配层不得依赖 ----

    SubmitService submit() {
        return submit;
    }

    EntryRepository entries() {
        return entries;
    }

    DirectoryRepository directory() {
        return directory;
    }

    RequestRepository requests() {
        return requests;
    }

    DeliveryRepository deliveries() {
        return deliveries;
    }

    EventRepository events() {
        return events;
    }

    /** 绑定到具体接入实例的入口端口，供受信控制器使用。 */
    IngressPort ingress(String entryRef) {
        EntryConfig entry = entries.find(entryRef);
        if (entry == null) {
            throw new IllegalStateException("unknown entry: " + entryRef);
        }
        return hosts.hostFor(entry, "controller").ingress();
    }

    public void start() {
        connections.startAll();
    }

    /** 周期任务：回收陈旧派发、派发、投递、重试关联、连接监督。返回本轮动作数。 */
    public int tick() {
        durableInbox.sweep();
        int actions = 0;
        actions += requests.requeueStaleDispatches(clock.instant().minus(Duration.ofMinutes(5)));
        actions += dispatcher.dispatchPending(settings.dispatchBatch());
        actions += dispatcher.reconcileUnknown(10);
        actions += outbox.deliverPending(settings.outboxBatch());
        actions += outbox.reconcileUnknown(10);
        actions += registrationPump.pump(10);
        actions += ownershipRefresh.pump();
        actions += intake.reprocessPending(10);
        connections.tick();
        directory.purgeExpiredAdmissions(clock.instant());
        return actions;
    }

    @Override
    public void close() {
        connections.stopAll(StopReason.SHUTDOWN);
        dispatcher.shutdown();
    }
}
