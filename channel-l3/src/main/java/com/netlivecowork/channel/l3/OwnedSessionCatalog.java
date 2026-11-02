package com.netlivecowork.channel.l3;

import com.fasterxml.jackson.databind.JsonNode;
import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.infra.Digests;
import com.netlivecowork.channel.infra.Ids;
import java.io.IOException;
import java.time.Clock;
import java.util.*;

/** Verified owner catalog; physical identities stop here, before L2/page DTOs. */
final class OwnedSessionCatalog {
    private final BackendTransport client;
    private final BackendIdentityResolver identities;
    private final Clock clock;
    private final BackendRegistry registry;
    private final BackendProtocol protocol;
    OwnedSessionCatalog(BackendTransport client, BackendIdentityResolver identities, Clock clock,
                        BackendRegistry registry, BackendProtocol protocol) {
        this.client=client;this.identities=identities;this.clock=clock;this.registry=registry;this.protocol=protocol;
    }
    record Discovery(SessionAccess.Catalog catalog,TrustedCall call,List<TargetRegistration> registrations) {}
    Discovery discover(TrustedCall call) throws IOException, InterruptedException {
        List<SessionAccess.Candidate> candidates=new ArrayList<>(); List<TargetRegistration> registrations=new ArrayList<>(); boolean authenticated=false;
        for (BackendService service:registry.services(call.tenantRef(),call.workspaceRef())) {
            var identity=identities.catalogIdentity(call,service.serviceRef());
            if(identity==null) continue;
            if(identity.validUntil()==null || !identity.validUntil().isAfter(clock.instant()))
                throw new ChannelContractException(ChannelErrorCode.AUTH_REQUIRED,"W3 authentication has expired");
            JsonNode caps=protocol.capabilities(service);
            if(!caps.path("features").path("session_catalog").asBoolean())
                throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CAPABILITY,"backend has no authenticated session catalog");
            String cursor=null; Set<String> cursors=new HashSet<>();
            for(int page=0;page<100;page++) {
                String path=BackendProtocol.PREFIX+"/session-catalog"+(cursor==null?"":"?after="+PythonBackendClient.encode(cursor));
                var reply=client.exchange(service,"GET",path,null,Map.of("X-Channel-Delegation",identity.protectedProof()));
                if(reply.status()==401 || reply.status()==403)
                    throw new ChannelContractException(ChannelErrorCode.AUTH_REQUIRED,"Current verified W3 session is required");
                JsonNode body=BackendProtocol.successful(reply);
                Json.onlyFields(body,"backend_id","principal","items","next_cursor");
                JsonNode principal=Json.obj(body,"principal");Json.onlyFields(principal,"subject_issuer","subject");
                var registration=protocol.registration(service);
                if(!registration.backendId().equals(Json.text(body,"backend_id"))
                    || !call.principalRef().equals(identities.principalRef(service.serviceRef(),Json.text(principal,"subject_issuer"),Json.text(principal,"subject"))))
                    throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Catalog identity does not match the actual caller");
                authenticated=true;
                JsonNode items=body.get("items");
                if(items==null || !items.isArray() || items.size()>100)
                    throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE,"Invalid backend catalog page");
                for(JsonNode item:items) {
                    Json.onlyFields(item,"session_id","resource_epoch","revision","title","status","workspace_label");
                    String session=Json.text(item,"session_id"), title=Json.text(item,"title");
                    long epoch=Json.reqLong(item,"resource_epoch");
                    var existing=registry.targetBySession(service.serviceRef(),session);
                    if(existing!=null && (!existing.tenantRef().equals(call.tenantRef()) || !existing.workspaceRef().equals(call.workspaceRef())))
                        throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Session belongs to a different resource domain");
                    if(existing!=null && !existing.enabled()) continue;
                    if(existing!=null && existing.resourceEpoch()!=epoch)
                        throw new ChannelContractException(ChannelErrorCode.BINDING_CONFLICT,"Session incarnation changed; explicit resource registration is required");
                    String target=existing==null?"target-"+Digests.sha256(String.join("\u0000",call.tenantRef(),call.workspaceRef(),service.serviceRef(),session,Long.toString(epoch))).substring(0,32):existing.targetRef();
                    var registrationRow=new TargetRegistration(target,call.tenantRef(),call.workspaceRef(),null,
                        service.serviceRef(),session,epoch,title,existing==null?null:existing.agentRef(),
                        existing==null?List.of("text"):existing.capabilities(),TargetSummary.Availability.ONLINE,
                        existing==null?1:existing.bindingRevision(),true);
                    TargetOwnership ownership=protocol.facts(service,registrationRow);
                    if(!call.principalRef().equals(ownership.ownerPrincipalRef()))
                        throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Catalog item ownership is not verified for the actual caller");
                    registrations.add(registrationRow);
                    candidates.add(new SessionAccess.Candidate(new TargetSummary(target,registrationRow.agentRef(),title,
                        registrationRow.capabilities(),TargetSummary.Availability.ONLINE),ownership,
                        Json.text(item,"workspace_label"),Json.text(item,"status")));
                }
                cursor=Json.optText(body,"next_cursor");
                if(cursor==null) break;
                if(!cursors.add(cursor) || page==99)
                    throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE,"Catalog pagination did not complete");
            }
        }
        if(!authenticated) throw new ChannelContractException(ChannelErrorCode.AUTH_REQUIRED,"No verified W3 identity is configured for this caller");
        return new Discovery(new SessionAccess.Catalog(call.principalRef(),Ids.next("catalog-auth"),clock.instant(),candidates),call,List.copyOf(registrations));
    }
}
