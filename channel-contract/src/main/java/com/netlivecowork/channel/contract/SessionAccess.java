package com.netlivecowork.channel.contract;

import java.time.Instant;
import java.util.List;

/** Personal Channel exposure management DTOs; selecting a session never creates resource rights. */
public final class SessionAccess {
    private SessionAccess() {}
    /** Only a trusted backend adapter produces this verified catalog; no physical routing is public. */
    /** Internal authenticated CoWork management context; never deserialized from page JSON. */
    public record Manager(String principalRef,String authenticationRef,Instant validUntil,String subjectIssuer,String subject) {}
    public record Candidate(TargetSummary summary, TargetOwnership ownership, String workspaceLabel, String status) {}
    public record Catalog(String principalRef, String authenticationRef, Instant verifiedAt, List<Candidate> candidates) {
        public Catalog { candidates=List.copyOf(candidates); }
    }
    public record Item(String targetRef, String label, String workspaceLabel, String status, boolean selected) {}
    public record View(long revision, String principalRef, List<Item> items) { public View { items=List.copyOf(items); } }
    public record Save(String requestId, long expectedRevision, List<String> targetRefs, List<String> workspaceRefs) {
        public Save(String requestId, long expectedRevision, List<String> targetRefs) {
            this(requestId, expectedRevision, targetRefs, null);
        }
        public Save {
            targetRefs=targetRefs==null?List.of():List.copyOf(targetRefs);
            if(workspaceRefs!=null)workspaceRefs=List.copyOf(workspaceRefs);
        }
    }
    public record Saved(String requestId, long revision, List<String> targetRefs) {
        public Saved { targetRefs=List.copyOf(targetRefs); }
    }
}
