package com.netlivecowork.channel.l2;

import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.port.BackendAccessPort;
import com.netlivecowork.channel.infra.Db;
import com.netlivecowork.channel.infra.Digests;
import com.netlivecowork.channel.l2.store.DirectoryRepository;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** Real-principal configuration facade; backend calls never run in a database transaction. */
final class UserSessionAccessService {
    private final Db db;private final Clock clock;private final DirectoryRepository directory;
    private final BackendAccessPort backend;private final GrantService grants;private final PersonalSessionPolicy policy;
    UserSessionAccessService(Db db,Clock clock,DirectoryRepository directory,BackendAccessPort backend,GrantService grants) {
        this.db=db;this.clock=clock;this.directory=directory;this.backend=backend;this.grants=grants;this.policy=new PersonalSessionPolicy(db);
    }
    private record Verified(TrustedCall call,SessionAccess.Catalog catalog,Set<String> existingTargets) {}
    private Verified verify(TrustedCall call,Runnable guard) {
        return verify(call, guard, true);
    }

    private Verified verify(TrustedCall call,Runnable guard,boolean legacySelection) {
        guard.run();
        Set<String> before=new HashSet<>(directory.targets(call.tenantRef(),call.workspaceRef()).stream().map(DirectoryRepository.TargetRow::targetRef).toList());
        SessionAccess.Catalog catalog;
        try { catalog=backend.discoverOwnedSessions(call).toCompletableFuture().get(25,TimeUnit.SECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw unavailable(); }
        catch(ExecutionException e) {
            if(e.getCause() instanceof ChannelContractException c) throw c;
            throw unavailable();
        } catch(TimeoutException e) { throw unavailable(); }
        if(catalog==null || !call.principalRef().equals(catalog.principalRef()) || catalog.authenticationRef()==null
            || catalog.verifiedAt()==null || catalog.verifiedAt().isBefore(clock.instant().minusSeconds(30))
            || catalog.verifiedAt().isAfter(clock.instant().plusSeconds(5)))
            throw new ChannelContractException(ChannelErrorCode.AUTH_REQUIRED,"Verified current W3 identity is required");
        guard.run();
        // Freeze only already-enrolled rights. Discovering a new real session does
        // not by itself open it to this personal Channel identity.
        db.tx(c->{
            guard.run();
            for(var candidate:catalog.candidates()) if(before.contains(candidate.summary().targetRef())) {
                var o=candidate.ownership();
                if(o==null || !call.principalRef().equals(o.ownerPrincipalRef()) || o.validUntil()==null || !o.validUntil().isAfter(clock.instant()))
                    throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Existing resource ownership cannot be verified");
                directory.applyOwnership(o.targetRef(),call.tenantRef(),call.workspaceRef(),o.ownerPrincipalRef(),o.resourceEpoch(),o.revision(),o.validUntil());
            }
            if (legacySelection)
                policy.seed(call.tenantRef(),call.workspaceRef(),call.principalRef(),before.stream()
                    .filter(ref->grants.canConfigureTarget(call.tenantRef(),call.workspaceRef(),call.principalRef(),ref)).sorted().toList());
            return null;
        });
        try { backend.registerCatalogTargets(call,catalog.authenticationRef()).toCompletableFuture().get(10,TimeUnit.SECONDS); }
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw unavailable();}
        catch(Exception e) {throw unavailable();}
        db.tx(c->{
            guard.run();
            for(var candidate:catalog.candidates()) {
                var s=candidate.summary();var o=candidate.ownership();
                if(s==null || o==null || !s.targetRef().equals(o.targetRef()) || !call.principalRef().equals(o.ownerPrincipalRef())
                    || o.validUntil()==null || !o.validUntil().isAfter(clock.instant()) || o.revision()<1)
                    throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Candidate resource ownership is not currently verified");
                directory.upsertTarget(new DirectoryRepository.TargetRow(s.targetRef(),call.tenantRef(),call.workspaceRef(),s.agentRef(),s.label(),s.capabilities(),s.availability(),null,1));
                directory.applyOwnership(s.targetRef(),call.tenantRef(),call.workspaceRef(),o.ownerPrincipalRef(),o.resourceEpoch(),o.revision(),o.validUntil());
                if(!call.principalRef().equals(directory.targetOwner(s.targetRef()))
                    || !grants.canConfigureTarget(call.tenantRef(),call.workspaceRef(),call.principalRef(),s.targetRef()))
                    throw new ChannelContractException(ChannelErrorCode.BINDING_CONFLICT,"Resource ownership changed while loading the catalog");
            }
            return null;
        });
        return new Verified(call,catalog,before);
    }
    SessionAccess.View get(TrustedCall call,Runnable guard) {
        var verified=verify(call,guard);
        return db.tx(c->{guard.run();return view(verified);});
    }
    SessionAccess.View available(TrustedCall call, Runnable guard) {
        var verified = verify(call, guard, false);
        return db.tx(c -> { guard.run(); return view(verified); });
    }
    private SessionAccess.View view(Verified v) {
        var call=v.call();
        return view(v, policy.state(call.tenantRef(),call.workspaceRef(),call.principalRef()));
    }
    private SessionAccess.View view(Verified v, PersonalSessionPolicy.State state) {
        var call = v.call();
        Map<String,SessionAccess.Candidate> owned=new HashMap<>();v.catalog().candidates().forEach(c->owned.put(c.summary().targetRef(),c));
        List<SessionAccess.Item> items=new ArrayList<>();
        for(var row:directory.targets(call.tenantRef(),call.workspaceRef())) {
            if(!grants.canConfigureTarget(call.tenantRef(),call.workspaceRef(),call.principalRef(),row.targetRef())) continue;
            var candidate=owned.get(row.targetRef());
            items.add(new SessionAccess.Item(row.targetRef(),row.label(),candidate==null?"已获准的会话":candidate.workspaceLabel(),
                candidate==null?row.availability().name().toLowerCase():candidate.status(),state!=null && state.targets().contains(row.targetRef())));
        }
        return new SessionAccess.View(state==null ? 0 : state.revision(),call.principalRef(),items);
    }
    SessionAccess.Saved save(TrustedCall call,SessionAccess.Save request,Runnable guard) {
        return save(call,request,guard,null);
    }
    SessionAccess.Saved save(TrustedCall call,SessionAccess.Save request,Runnable guard,Set<String> eligibleWorkspaces) {
        if(request==null || request.requestId()==null || request.requestId().isBlank() || request.requestId().length()>256
            || request.expectedRevision()<0 || request.targetRefs().size()>200 || request.targetRefs().stream().anyMatch(r->r==null||r.isBlank())
            || new HashSet<>(request.targetRefs()).size()!=request.targetRefs().size())
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,"requestId, current revision and unique session selections are required");
        if(request.workspaceRefs()!=null && (eligibleWorkspaces==null || request.workspaceRefs().size()>200
                || request.workspaceRefs().size()!=new HashSet<>(request.workspaceRefs()).size()))
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,"Unique workspace references are required");
        var verified=verify(call,guard);
        List<String> selected=request.targetRefs().stream().sorted().toList();
        String key=key(call,request.requestId());
        var parameters=Json.obj().put("revision",request.expectedRevision());
        parameters.set("targets",Json.mapper().valueToTree(selected));
        var creation=request.workspaceRefs()==null?null:request.workspaceRefs().stream().sorted().toList();
        if(creation!=null)parameters.set("workspaces",Json.mapper().valueToTree(creation));
        String digest=Digests.sha256(Json.write(parameters));
        return db.tx(c->{
            guard.run();
            var prior=db.queryOne("SELECT request_digest,snapshot FROM l2_personal_session_requests WHERE request_key=?",r->List.of(r.getString(1),r.getString(2)),key);
            if(prior!=null) {
                if(!prior.get(0).equals(digest)) throw new ChannelContractException(ChannelErrorCode.REQUEST_CONFLICT,"requestId is already bound to different access settings");
                return readable(call,saved(Json.read(prior.get(1))));
            }
            Set<String> eligible=new HashSet<>(view(verified).items().stream().map(SessionAccess.Item::targetRef).toList());
            if(!eligible.containsAll(selected)) throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"A selected session is not currently authorized for this actual user");
            if(creation!=null && !eligibleWorkspaces.containsAll(creation))
                throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"A selected workspace does not allow session creation");
            var before=policy.state(call.tenantRef(),call.workspaceRef(),call.principalRef());
            if(before.revision()!=request.expectedRevision()) throw new ChannelContractException(ChannelErrorCode.GRANT_REVISION_CONFLICT,"Access configuration changed; reload and review the current selection");
            boolean creationChanged=creation!=null && !creation.equals(creationRefs(call));
            if(!before.targets().equals(selected)||creationChanged)policy.replace(call.tenantRef(),call.workspaceRef(),call.principalRef(),before,selected);
            if(creationChanged)policy.saveCreationRefs(call.tenantRef(),call.workspaceRef(),call.principalRef(),creation);
            var current=policy.state(call.tenantRef(),call.workspaceRef(),call.principalRef());
            var snapshot=Json.obj().put("requestId",request.requestId()).put("revision",current.revision());
            snapshot.set("targetRefs",Json.mapper().valueToTree(current.targets()));
            db.update("INSERT INTO l2_personal_session_requests VALUES(?,?,?)",key,digest,Json.write(snapshot));
            return new SessionAccess.Saved(request.requestId(),current.revision(),current.targets());
        });
    }
    List<String> creationRefs(TrustedCall call) {
        return policy.creationRefs(call.tenantRef(),call.workspaceRef(),call.principalRef());
    }
    long accessGeneration(TrustedCall call,String ref,boolean workspace) {
        return workspace ? policy.creationGeneration(call.tenantRef(),call.workspaceRef(),call.principalRef(),ref)
            : policy.generation(call.tenantRef(),call.workspaceRef(),call.principalRef(),ref);
    }
    SessionAccess.Saved lookup(TrustedCall call,String requestId,Runnable guard) {
        verify(call,guard);
        return db.tx(c->{guard.run();String value=db.queryOne("SELECT snapshot FROM l2_personal_session_requests WHERE request_key=?",r->r.getString(1),key(call,requestId));
            if(value==null)throw new ChannelContractException(ChannelErrorCode.REQUEST_NOT_FOUND,"No confirmed access save exists for the original request");
            return readable(call,saved(Json.read(value)));});
    }
    private SessionAccess.Saved readable(TrustedCall call,SessionAccess.Saved value) {
        for(String ref:value.targetRefs()) if(!grants.canConfigureTarget(call.tenantRef(),call.workspaceRef(),call.principalRef(),ref))
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Original configuration contains a session no longer accessible to this user");
        return value;
    }
    private SessionAccess.Saved saved(com.fasterxml.jackson.databind.JsonNode n) {
        List<String> refs=new ArrayList<>();n.path("targetRefs").forEach(r->refs.add(r.asText()));
        return new SessionAccess.Saved(n.path("requestId").asText(),n.path("revision").asLong(),refs);
    }
    private String key(TrustedCall c,String request) {return Digests.sha256(String.join("\u0000",c.tenantRef(),c.workspaceRef(),c.principalRef(),request));}
    private ChannelContractException unavailable(){return new ChannelContractException(ChannelErrorCode.UNAVAILABLE,"Cannot verify the current W3 account and real sessions; settings were not saved");}
}
