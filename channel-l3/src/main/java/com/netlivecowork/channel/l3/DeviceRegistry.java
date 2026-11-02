package com.netlivecowork.channel.l3;

import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.infra.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;

/** Authoritative single-instance device credentials, enrollment and monotonically increasing generations. */
public final class DeviceRegistry {
    public record Credential(String deviceRef, String token) {}
    private final Db db;
    private final Clock clock;
    private final BackendRegistry backends;
    private final DeviceSecrets secrets;
    DeviceRegistry(Db db, Clock clock, BackendRegistry backends, DeviceSecrets secrets) {
        this.db=db; this.clock=clock; this.backends=backends; this.secrets=secrets;
        db.ddl("""
            CREATE TABLE IF NOT EXISTS connector_devices(
              device_ref TEXT PRIMARY KEY,tenant_ref TEXT NOT NULL,workspace_ref TEXT NOT NULL,
              token_digest TEXT,credential_epoch INTEGER NOT NULL DEFAULT 0,
              generation INTEGER NOT NULL DEFAULT 0,revoked INTEGER NOT NULL DEFAULT 0)
            """, """
            CREATE TABLE IF NOT EXISTS connector_enrollments(
              request_id TEXT PRIMARY KEY,device_ref TEXT NOT NULL,ticket_digest TEXT NOT NULL UNIQUE,
              credential_epoch INTEGER NOT NULL,expires_at TEXT NOT NULL,
              exchange_digest TEXT,protected_token TEXT)
            """, "CREATE TABLE IF NOT EXISTS connector_service_barriers(service_ref TEXT PRIMARY KEY, revoked_before TEXT NOT NULL)");
    }
    public DeviceAccess.Ticket issue(DeviceAccess.Authorization grant) {
        BackendService service=backends.service(grant.serviceRef());
        if(service==null || !service.enabled() || service.accessType()!=AccessType.CONNECTOR
            || !service.deviceRef().equals(grant.deviceRef()) || !service.tenantRef().equals(grant.tenantRef())
            || !service.workspaceRef().equals(grant.workspaceRef()))
            throw denied("Enrollment does not match the trusted connector service");
        // Fail before creating a ticket if protected exchange recovery cannot be supplied.
        secrets.open(secrets.seal("device-enrollment-protection-check"));
        String ticket=random(); Instant expires=clock.instant().plusSeconds(300);
        db.tx(c->{
            if(db.queryOne("SELECT request_id FROM connector_enrollments WHERE request_id=?",rs->rs.getString(1),grant.requestId())!=null)
                throw new ChannelContractException(ChannelErrorCode.REQUEST_CONFLICT,"Enrollment was already issued; no second ticket is created");
            db.update("INSERT OR IGNORE INTO connector_devices(device_ref,tenant_ref,workspace_ref) VALUES(?,?,?)",
                grant.deviceRef(),grant.tenantRef(),grant.workspaceRef());
            var domain=db.queryOne("SELECT tenant_ref,workspace_ref FROM connector_devices WHERE device_ref=?",
                rs->java.util.List.of(rs.getString(1),rs.getString(2)),grant.deviceRef());
            if(!domain.get(0).equals(grant.tenantRef())||!domain.get(1).equals(grant.workspaceRef()))
                throw denied("Device belongs to another trusted resource domain");
            Long epoch=db.queryOne("SELECT credential_epoch FROM connector_devices WHERE device_ref=?",rs->rs.getLong(1),grant.deviceRef());
            db.update("INSERT INTO connector_enrollments(request_id,device_ref,ticket_digest,credential_epoch,expires_at) VALUES(?,?,?,?,?)",
                grant.requestId(),grant.deviceRef(),Digests.sha256(ticket),epoch,expires);
            return null;
        });
        return new DeviceAccess.Ticket(grant.deviceRef(),ticket,expires);
    }
    public Credential redeem(String ticket,String exchangeSecret) {
        if(ticket==null || ticket.length()<32 || exchangeSecret==null || exchangeSecret.length()<32 || exchangeSecret.length()>256)
            throw denied("A valid original enrollment exchange is required");
        return db.tx(c->{
            var row=db.queryOne("SELECT * FROM connector_enrollments WHERE ticket_digest=?",rs-> new Enrollment(
                rs.getString("device_ref"),rs.getLong("credential_epoch"),Instant.parse(rs.getString("expires_at")),
                rs.getString("exchange_digest"),rs.getString("protected_token")),Digests.sha256(ticket));
            if(row==null || !row.expires().isAfter(clock.instant()))throw denied("Enrollment is invalid or expired");
            String exchange=Digests.sha256(exchangeSecret);
            Long epoch=db.queryOne("SELECT credential_epoch FROM connector_devices WHERE device_ref=?",rs->rs.getLong(1),row.device());
            if(row.exchange()!=null) {
                Integer revoked=db.queryOne("SELECT revoked FROM connector_devices WHERE device_ref=?",rs->rs.getInt(1),row.device());
                if(!constant(row.exchange(),exchange) || epoch!=row.epoch()+1 || revoked!=0)throw denied("Enrollment exchange cannot be reused");
                return new Credential(row.device(),secrets.open(row.protectedToken()));
            }
            if(epoch!=row.epoch() || backends.deviceServices(row.device()).isEmpty())throw denied("Enrollment is no longer current");
            String token=random();String protectedToken=secrets.seal(token);
            db.update("UPDATE connector_devices SET token_digest=?,credential_epoch=credential_epoch+1,revoked=0 WHERE device_ref=?",
                Digests.sha256(token),row.device());
            db.update("UPDATE connector_enrollments SET exchange_digest=?,protected_token=? WHERE ticket_digest=?",
                exchange,protectedToken,Digests.sha256(ticket));
            return new Credential(row.device(),token);
        });
    }
    public String authenticate(String token) {
        if(token==null || token.length()>256)throw denied("Device authentication is required");
        String device=db.queryOne("SELECT device_ref FROM connector_devices WHERE token_digest=? AND revoked=0",
            rs->rs.getString(1),Digests.sha256(token));
        if(device==null || backends.deviceServices(device).isEmpty())throw denied("Device authentication failed");
        return device;
    }
    long connect(String token) {
        return db.tx(c->{String device=authenticate(token);
            db.update("UPDATE connector_devices SET generation=generation+1 WHERE device_ref=?",device);
            return db.queryOne("SELECT generation FROM connector_devices WHERE device_ref=?",rs->rs.getLong(1),device);});
    }
    boolean current(String device,String token,long generation) {
        return db.queryOne("SELECT device_ref FROM connector_devices WHERE device_ref=? AND token_digest=? AND generation=? AND revoked=0",
            rs->rs.getString(1),device,Digests.sha256(token),generation)!=null;
    }
    void requireScope(String device,String tenant,String workspace){
        if(db.queryOne("SELECT device_ref FROM connector_devices WHERE device_ref=? AND tenant_ref=? AND workspace_ref=?",
            rs->rs.getString(1),device,tenant,workspace)==null)
            throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,"Device does not belong to this management scope");
    }
    boolean permitsOriginal(String serviceRef,Instant packetCreatedAt){
        String barrier=db.queryOne("SELECT revoked_before FROM connector_service_barriers WHERE service_ref=?",rs->rs.getString(1),serviceRef);
        return barrier==null || packetCreatedAt.isAfter(Instant.parse(barrier));
    }
    public void revoke(String device) {
        db.tx(c->{
            Instant now=clock.instant();
            db.update("UPDATE connector_devices SET revoked=1,credential_epoch=credential_epoch+1,generation=generation+1 WHERE device_ref=?",device);
            for(BackendService service:backends.allDeviceServices(device)){
                db.update("INSERT INTO connector_service_barriers VALUES(?,?) ON CONFLICT(service_ref) DO UPDATE SET revoked_before=excluded.revoked_before",
                    service.serviceRef(),now);
                // Only authoritative, provably unsent rows can be closed. Accepted/unknown retain reconciliation.
                db.update("""
                    INSERT OR IGNORE INTO dispatch_closures(operation_ref,target_ref,tenant_ref,workspace_ref,payload_digest,closed_at)
                    SELECT operation_ref,target_ref,?,?,payload_digest,? FROM dispatches
                    WHERE service_ref=? AND phase IN ('REGISTERED','NOT_SENT')
                    """,service.tenantRef(),service.workspaceRef(),now,service.serviceRef());
            }
            return null;
        });
    }
    public boolean revoked(String device) {
        Integer value=db.queryOne("SELECT revoked FROM connector_devices WHERE device_ref=?",rs->rs.getInt(1),device);
        return value!=null && value!=0;
    }
    private record Enrollment(String device,long epoch,Instant expires,String exchange,String protectedToken) {}
    private static String random() {byte[] raw=new byte[32];new SecureRandom().nextBytes(raw);return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);}
    private static boolean constant(String a,String b){return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    private static ChannelContractException denied(String text){return new ChannelContractException(ChannelErrorCode.AUTH_REQUIRED,text);}
}
