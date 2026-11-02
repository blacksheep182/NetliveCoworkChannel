package com.netlivecowork.channel.l2;

import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.infra.Db;
import java.util.*;

/** Additional personal exposure restriction. It cannot create owner/Grant authority. */
final class PersonalSessionPolicy {
    private final Db db;
    PersonalSessionPolicy(Db db) {
        this.db=db;
        db.ddl("""
          CREATE TABLE IF NOT EXISTS l2_personal_session_access(
            tenant_ref TEXT NOT NULL,workspace_ref TEXT NOT NULL,principal_ref TEXT NOT NULL,
            revision INTEGER NOT NULL,target_refs TEXT NOT NULL,
            PRIMARY KEY(tenant_ref,workspace_ref,principal_ref))
          ""","""
          CREATE TABLE IF NOT EXISTS l2_personal_session_denials(
            tenant_ref TEXT NOT NULL,workspace_ref TEXT NOT NULL,principal_ref TEXT NOT NULL,
            target_ref TEXT NOT NULL,generation INTEGER NOT NULL,
            PRIMARY KEY(tenant_ref,workspace_ref,principal_ref,target_ref))
          ""","""
          CREATE TABLE IF NOT EXISTS l2_personal_session_requests(
            request_key TEXT PRIMARY KEY,request_digest TEXT NOT NULL,snapshot TEXT NOT NULL)
          """);
    }
    record State(long revision,List<String> targets) {}
    State state(String tenant,String workspace,String principal) {
        return db.queryOne("SELECT revision,target_refs FROM l2_personal_session_access WHERE tenant_ref=? AND workspace_ref=? AND principal_ref=?",
            r->{ List<String> refs=new ArrayList<>();Json.read(r.getString(2)).forEach(n->refs.add(n.asText()));return new State(r.getLong(1),List.copyOf(refs));},tenant,workspace,principal);
    }
    void seed(String tenant,String workspace,String principal,List<String> targets) {
        db.update("INSERT OR IGNORE INTO l2_personal_session_access VALUES(?,?,?,0,?)",tenant,workspace,principal,Json.write(Json.mapper().valueToTree(targets)));
    }
    boolean allows(String tenant,String workspace,String principal,String target) {
        State s=state(tenant,workspace,principal);return s==null || s.targets().contains(target);
    }
    long generation(String tenant,String workspace,String principal,String target) {
        Long g=db.queryOne("SELECT generation FROM l2_personal_session_denials WHERE tenant_ref=? AND workspace_ref=? AND principal_ref=? AND target_ref=?",
            r->r.getLong(1),tenant,workspace,principal,target);return g==null?0:g;
    }
    void replace(String tenant,String workspace,String principal,State before,List<String> selected) {
        for(String target:before.targets()) if(!selected.contains(target)) db.update("""
            INSERT INTO l2_personal_session_denials VALUES(?,?,?,?,1)
            ON CONFLICT(tenant_ref,workspace_ref,principal_ref,target_ref) DO UPDATE SET generation=generation+1
            """,tenant,workspace,principal,target);
        int changed=db.update("UPDATE l2_personal_session_access SET revision=revision+1,target_refs=? WHERE tenant_ref=? AND workspace_ref=? AND principal_ref=? AND revision=?",
            Json.write(Json.mapper().valueToTree(selected)),tenant,workspace,principal,before.revision());
        if(changed!=1) throw new com.netlivecowork.channel.contract.ChannelContractException(
            com.netlivecowork.channel.contract.ChannelErrorCode.GRANT_REVISION_CONFLICT,"Access configuration changed; reload before saving");
    }
}
