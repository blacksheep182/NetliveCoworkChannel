package com.netlivecowork.channel.app;

import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.TrustedCall;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.l3.BackendCallBinding;
import com.netlivecowork.channel.l3.BackendIdentityResolver;
import com.netlivecowork.channel.l3.ProtocolJson;
import com.netlivecowork.channel.l3.ServiceCredentialResolver;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Uses each actual operator's existing W3 proof, with a short-lived HTTP request binding. */
final class W3BackendIdentityResolver implements BackendIdentityResolver {
    private final ChannelProperties properties;
    private final ServiceCredentialResolver credentials;
    private final Clock clock;
    private final java.util.concurrent.ConcurrentHashMap<String,String> verifiedDesktopProofs=new java.util.concurrent.ConcurrentHashMap<>();
    void useVerifiedDesktopProof(String serviceRef,com.netlivecowork.channel.contract.SessionAccess.Manager manager,String opaque) {
        var envelope=Json.read(new String(Base64.getUrlDecoder().decode(opaque),StandardCharsets.UTF_8));
        Json.onlyFields(envelope,"uid","session_proof");
        if(!Objects.equals(Json.text(envelope,"uid"),manager.subject())) throw new SecurityException();
        verifiedDesktopProofs.put(serviceRef+"\u0000"+manager.principalRef(),Json.text(envelope,"session_proof"));
    }
    private String proof(String serviceRef,ChannelProperties.Principal principal) {
        String current=verifiedDesktopProofs.get(serviceRef+"\u0000"+principal.principalRef());
        return current==null?credentials.resolve(principal.sessionProofRef()):current;
    }

    W3BackendIdentityResolver(ChannelProperties properties, ServiceCredentialResolver credentials, Clock clock) {
        this.properties = properties;
        this.credentials = credentials;
        this.clock = clock;
        var services = new HashSet<String>();
        var registrations = new HashSet<Registration>();
        for (var service : properties.services()) {
            if (!services.add(service.serviceRef())) throw new IllegalArgumentException("duplicate backend service");
            var registration = registration(service.serviceRef());
            if (registration != null && !registrations.add(registration))
                throw new IllegalArgumentException("duplicate backend identity registration");
            var principals = new HashSet<String>();
            var subjects = new HashSet<String>();
            for (var principal : service.principals()) {
                if (!text(principal.principalRef()) || !text(principal.subject())
                    || !"w3".equals(principal.subjectIssuer())
                    || !principals.add(principal.principalRef()) || !subjects.add(principal.subject()))
                    throw new IllegalArgumentException("invalid or ambiguous backend principal mapping");
            }
        }
    }

    @Override
    public Registration registration(String serviceRef) {
        var service = properties.services().stream().filter(item -> item.serviceRef().equals(serviceRef)
            && item.enabled()).findFirst().orElse(null);
        if (service == null || !text(service.backendId()) || !text(service.clientId())
            || !text(service.backendTenantId()) || !text(service.backendWorkspaceId())
            || !"w3-desktop-session".equals(service.adapterId())) return null;
        return new Registration(service.backendId(), service.clientId(), service.backendTenantId(),
            service.backendWorkspaceId(), service.adapterId());
    }

    @Override
    public String principalRef(String serviceRef, String issuer, String subject) {
        return properties.services().stream().filter(item -> item.enabled() && item.serviceRef().equals(serviceRef))
            .flatMap(service -> service.principals().stream())
            .filter(principal -> Objects.equals(principal.subjectIssuer(), issuer)
                && Objects.equals(principal.subject(), subject))
            .map(ChannelProperties.Principal::principalRef).findFirst().orElse(null);
    }

    @Override
    public Delegation obtain(TrustedCall call, BackendCallBinding binding) {
        if (!properties.tenantRef().equals(call.tenantRef())
            || !properties.workspaceRef().equals(call.workspaceRef())) return null;
        var service = properties.services().stream().filter(item -> item.enabled()
            && Objects.equals(item.backendId(), binding.backendId())
            && Objects.equals(item.clientId(), binding.clientId())
            && Objects.equals(item.backendTenantId(), binding.tenantId())
            && Objects.equals(item.backendWorkspaceId(), binding.workspaceId())
            && "w3-desktop-session".equals(item.adapterId())).findFirst().orElse(null);
        if (service == null) return null;
        var principal = service.principals().stream()
            .filter(item -> item.principalRef().equals(call.principalRef())).findFirst().orElse(null);
        if (principal == null || !text(principal.sessionProofRef())) return null;
        Instant expires = clock.instant().plusSeconds(45);
        if (call.deadline() != null && call.deadline().isBefore(expires)) expires = call.deadline();
        if (!expires.isAfter(clock.instant())) return null;
        String proof = proof(service.serviceRef(),principal);
        if (!text(proof)) return null;
        var payload = Json.obj().put("backend_id", binding.backendId()).put("client_id", binding.clientId())
            .put("tenant_id", binding.tenantId()).put("workspace_id", binding.workspaceId())
            .put("resource_epoch", binding.resourceEpoch()).put("session_id", binding.sessionId())
            .put("subject_issuer", principal.subjectIssuer()).put("subject", principal.subject())
            .put("action", binding.action()).put("http_method", binding.method())
            .put("http_path", binding.path()).put("body_sha256", binding.bodySha256())
            .put("expires_at", expires.toString());
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(proof.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = HexFormat.of().formatHex(mac.doFinal(
                ProtocolJson.canonical(payload).getBytes(StandardCharsets.UTF_8)));
            var envelope = Json.obj().put("uid", principal.subject())
                .put("expires_at", expires.toString()).put("signature", signature);
            String opaque = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ProtocolJson.canonical(envelope).getBytes(StandardCharsets.UTF_8));
            return new Delegation(opaque, principal.subjectIssuer(), principal.subject(), expires);
        } catch (Exception error) {
            throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE, "W3 request binding unavailable");
        }
    }

    @Override
    public Delegation catalogIdentity(TrustedCall call, String serviceRef) {
        if(!properties.tenantRef().equals(call.tenantRef()) || !properties.workspaceRef().equals(call.workspaceRef())) return null;
        var service=properties.services().stream().filter(s->s.enabled() && s.serviceRef().equals(serviceRef)
            && registration(serviceRef)!=null).findFirst().orElse(null);
        if(service==null) return null;
        var principal=service.principals().stream().filter(p->p.principalRef().equals(call.principalRef())).findFirst().orElse(null);
        if(principal==null || !text(principal.sessionProofRef())) return null;
        String proof=proof(serviceRef,principal);
        if(!text(proof)) return null;
        var envelope=Json.obj().put("uid",principal.subject()).put("session_proof",proof);
        String opaque=Base64.getUrlEncoder().withoutPadding().encodeToString(ProtocolJson.canonical(envelope).getBytes(StandardCharsets.UTF_8));
        Instant expiry=clock.instant().plusSeconds(30);
        if(call.deadline()!=null && call.deadline().isBefore(expiry)) expiry=call.deadline();
        return new Delegation(opaque,principal.subjectIssuer(),principal.subject(),expiry);
    }

    private static boolean text(String value) { return value != null && !value.isBlank(); }
}
