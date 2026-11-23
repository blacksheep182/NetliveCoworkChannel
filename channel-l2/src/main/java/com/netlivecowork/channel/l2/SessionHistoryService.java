package com.netlivecowork.channel.l2;

import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.adapter.AdapterCapabilities.HistoryAccess;
import com.netlivecowork.channel.contract.adapter.EntryConfig;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.port.*;
import com.netlivecowork.channel.infra.*;
import com.netlivecowork.channel.l2.store.RequestRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/** A pull query, never an event subscription or a platform delivery. */
final class SessionHistoryService {
    private final Db db; private final Clock clock; private final AdapterCatalog catalog;
    private final BackendAccessPort backend; private final GrantService grants; private final RequestRepository messages;
    SessionHistoryService(Db db, Clock clock, AdapterCatalog catalog, BackendAccessPort backend,
                          GrantService grants, RequestRepository messages) {
        this.db=db;this.clock=clock;this.catalog=catalog;this.backend=backend;this.grants=grants;this.messages=messages;
        db.ddl("""
            CREATE TABLE IF NOT EXISTS l2_history_cursors(
              token TEXT PRIMARY KEY,entry_ref TEXT NOT NULL,entry_epoch INTEGER NOT NULL,
              principal TEXT NOT NULL,target TEXT NOT NULL,basis_ref TEXT NOT NULL,
              backend_cursor TEXT NOT NULL,expires_at TEXT NOT NULL)
            """);
    }
    record Cursor(String entry, long epoch, String principal, String target, String basis,
                  String backendCursor, Instant expiry) {}
    private void requireFull(EntryConfig entry) {
        if (!entry.enabled() || AdapterQueryPolicy.access(catalog,entry)!=HistoryAccess.FULL)
            throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CAPABILITY,
                "This adapter does not expose conversation history; use the session summary query");
    }
    SessionHistory.Page query(EntryConfig entry, TrustedCall call, SessionHistory.Request request, Runnable guard) {
        return grants.withEntryPermissions(entry.entryRef(), false, () -> queryScoped(entry, call, request, guard));
    }

    private SessionHistory.Page queryScoped(EntryConfig entry, TrustedCall call, SessionHistory.Request request, Runnable guard) {
        requireFull(entry);guard.run();
        String basis;String backendCursor=null;Instant expiry;
        if(request.page().cursor()==null) {
            basis=Ids.next("hread");expiry=clock.instant().plusSeconds(1800);
            db.tx(c->{grants.captureHistory(basis,call,request.targetRef());return null;});
        } else {
            Cursor c=db.queryOne("SELECT * FROM l2_history_cursors WHERE token=?",r->new Cursor(
                r.getString("entry_ref"),r.getLong("entry_epoch"),r.getString("principal"),r.getString("target"),
                r.getString("basis_ref"),r.getString("backend_cursor"),Instant.parse(r.getString("expires_at"))),request.page().cursor());
            if(c==null || !c.entry().equals(entry.entryRef()) || c.epoch()!=entry.accountBindingEpoch()
                || !c.principal().equals(call.principalRef()) || !c.target().equals(request.targetRef())
                || !c.expiry().isAfter(clock.instant()))
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT,"History cursor is invalid for this identity or session; reload history");
            basis=c.basis();backendCursor=c.backendCursor();expiry=c.expiry();
        }
        requireCurrent(basis);
        SessionHistory.BackendPage page;
        try {
            page=backend.queryHistory(call,request.targetRef(),PageRequest.of(backendCursor,request.page().effectiveLimit()))
                .toCompletableFuture().get(15,TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if(e.getCause() instanceof ChannelContractException contract)throw contract;
            throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE,"History query failed; please retry");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE,"History query interrupted");
        } catch (java.util.concurrent.TimeoutException e) {
            throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE,"History query timed out; please retry");
        }
        if(page.items().size()>request.page().effectiveLimit())throw new ChannelContractException(
            ChannelErrorCode.UNAVAILABLE,"Backend exceeded the requested history page limit");
        guard.run();requireFull(entry);
        String originalBasis=basis;Instant originalExpiry=expiry;
        return grants.withEntryPermissions(entry.entryRef(), true, () -> db.tx(c->{
            guard.run();requireCurrent(originalBasis);
            var items=new ArrayList<SessionHistory.Message>();
            for(var item:page.items()) {
                String ref=messages.ensureMessage(request.targetRef(),"history",item.backendMessageId(),null,
                    Json.write(Json.arr().add(Json.obj().put("type","text").put("partId","text").put("text",item.text()))));
                items.add(new SessionHistory.Message(ref,item.role(),item.text(),item.unsupportedContent(),item.createdAt()));
            }
            String next=null;
            if(page.nextCursor()!=null) {
                next=Ids.next("hcursor");
                db.update("INSERT INTO l2_history_cursors VALUES(?,?,?,?,?,?,?,?)",next,entry.entryRef(),entry.accountBindingEpoch(),
                    call.principalRef(),request.targetRef(),originalBasis,page.nextCursor(),originalExpiry.toString());
            }
            return new SessionHistory.Page(request.targetRef(),items,next);
        }));
    }
    private void requireCurrent(String basis) {
        if(!grants.current(basis))throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,
            "Original history authorization is no longer valid");
    }
}
