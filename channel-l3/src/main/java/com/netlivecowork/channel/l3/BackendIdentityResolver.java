package com.netlivecowork.channel.l3;

import com.netlivecowork.channel.contract.TrustedCall;
import java.time.Instant;

/** Restricted identity/credential adapter. None of these values are public Packet fields. */
public interface BackendIdentityResolver {
    record Registration(String backendId, String clientId, String tenantId, String workspaceId, String adapterId) {}
    record Delegation(String protectedProof, String subjectIssuer, String subject, Instant validUntil) {
        @Override public String toString() { return "Delegation[redacted]"; }
    }
    Registration registration(String serviceRef);
    String principalRef(String serviceRef, String subjectIssuer, String subject);
    Delegation obtain(TrustedCall call, BackendCallBinding binding);
    /** Server-to-server proof only; never expose it to a page or use it as an owner string. */
    default Delegation catalogIdentity(TrustedCall call, String serviceRef) { return null; }

    BackendIdentityResolver UNAVAILABLE = new BackendIdentityResolver() {
        public Registration registration(String serviceRef) { return null; }
        public String principalRef(String serviceRef, String issuer, String subject) { return null; }
        public Delegation obtain(TrustedCall call, BackendCallBinding binding) { return null; }
    };
}
