package com.netlivecowork.channel.l2;

import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.adapter.EntryConfig;
import com.netlivecowork.channel.contract.json.PacketJson;
import com.netlivecowork.channel.infra.*;
import com.netlivecowork.channel.l2.store.*;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;

/** Policy/transaction owner for receiver custody, independently of outbox confirmation (SM15). */
final class DurableInboxService {
    private final Db db; private final Clock clock; private final InboxRepository inbox;
    private final DeliveryRepository deliveries; private final DirectoryRepository directory; private final GrantService grants;
    private Instant nextSweep=Instant.MIN;
    DurableInboxService(Db db,Clock clock,DeliveryRepository deliveries,DirectoryRepository directory,GrantService grants) {
        this.db=db;this.clock=clock;this.deliveries=deliveries;this.directory=directory;this.grants=grants;
        this.inbox=new InboxRepository(db);
    }
    void requirePersistent() {
        if(db.url().contains("mode=memory")) fail(ChannelErrorCode.UNAVAILABLE,"Reliable inbox requires a file database");
    }
    void verify(EntryConfig entry) {
        requirePersistent();setting(entry,"maxItems",100000,1,1000000);setting(entry,"retentionSeconds",2592000,60,31536000);setting(entry,"cursorTtlSeconds",1800,60,86400);
    }
    void sweep() {
        if(clock.instant().isBefore(nextSweep))return;
        inbox.expire(clock.instant().toString());nextSweep=clock.instant().plusSeconds(60);
    }
    DeliveryInbox.Receipt append(EntryConfig entry,String id,Runnable guard) {
        return db.tx(c->{
            guard.run();requirePersistent();
            var row=deliveries.delivery(id);
            if(row==null || !entry.entryRef().equals(row.entryRef()) || !entry.tenantRef().equals(row.tenantRef()) || !entry.workspaceRef().equals(row.workspaceRef()))
                fail(ChannelErrorCode.PERMISSION_DENIED,"Delivery is outside this entry");
            var destination=destination(entry,row.destinationRef(),row.destinationRevision());
            String mailbox=mailbox(entry,destination);
            var prior=inbox.find(id);
            if(prior!=null) {
                if(!mailbox.equals(prior.mailbox())) fail(ChannelErrorCode.PERMISSION_DENIED,"Delivery identity changed");
                if(prior.body()!=null && !prior.body().equals(row.deliveryJson()))fail(ChannelErrorCode.REQUEST_CONFLICT,"Immutable delivery content changed");
                return receipt(prior);
            }
            if(row.status()!=DeliveryRepository.DeliveryStatus.SENDING || !authorized(id,destination.callerRef()))
                fail(ChannelErrorCode.PERMISSION_DENIED,"Original delivery authorization is no longer valid");
            inbox.expire(clock.instant().toString());
            if(inbox.count(mailbox)>=setting(entry,"maxItems",100000,1,1000000)) fail(ChannelErrorCode.UNAVAILABLE,"Inbox capacity reached");
            inbox.append(mailbox,id,Ids.next("ireceipt"),row.deliveryJson(),clock.instant().toString(),clock.instant().plusSeconds(setting(entry,"retentionSeconds",2592000,60,31536000)).toString());
            return receipt(inbox.find(id));
        });
    }
    DeliveryReceipt custody(String id) {
        var item=inbox.find(id);
        return item==null?null:new DeliveryReceipt.Accepted(id,item.receipt());
    }
    DeliveryReceipt query(EntryConfig entry,String id,Runnable guard) {
        return db.tx(c->{
            guard.run();var row=deliveries.delivery(id);
            if(row==null || !entry.entryRef().equals(row.entryRef())) fail(ChannelErrorCode.PERMISSION_DENIED,"Delivery is outside this entry");
            var d=destination(entry,row.destinationRef(),row.destinationRevision());
            var item=inbox.find(id);
            if(item!=null && item.mailbox().equals(mailbox(entry,d)))return new DeliveryReceipt.Accepted(id,item.receipt());
            return new DeliveryReceipt.Unknown(id,ChannelErrorCode.OUTCOME_UNKNOWN.error("No conclusive receiver receipt"));
        });
    }
    DeliveryInbox.Result read(EntryConfig entry,DirectoryRepository.AdmissionRow a,DeliveryInbox.Request request,Runnable guard) {
        return db.tx(c->{
            guard.run();var d=destination(entry,a.replyTargetRef(),null);
            requireRecipient(d,a);String mail=mailbox(entry,d);
            var consumer=inbox.consumer(mail,request.consumerKey(),Ids.next("iconsumer"));
            long position=consumer.received();
            var cursor=request.cursor()==null?null:inbox.cursor(request.cursor());
            if(request.cursor()!=null) {
                requireCursor(cursor,consumer);
                if(!Instant.parse(cursor.expiry()).isAfter(clock.instant()))return gap(entry,consumer,"cursor_expired");
                if(cursor.resync()) {
                    if(cursor.end()<consumer.baseline())fail(ChannelErrorCode.INVALID_ARGUMENT,"Recovery baseline is stale");
                    if(cursor.end()>consumer.baseline())inbox.baseline(consumer,Math.max(consumer.received(),cursor.end()),clock.instant().toString());
                    consumer=inbox.consumer(consumer.ref());
                }
                else if(cursor.baseline()!=consumer.baseline()) fail(ChannelErrorCode.INVALID_ARGUMENT,"Cursor belongs to an older recovery baseline");
                position=cursor.end();
            }
            if(position<inbox.expiredThrough(mail,clock.instant().toString())) return gap(entry,consumer,"retention");
            var rows=inbox.items(mail,position,request.pageSize()+1);
            var items=new ArrayList<DeliveryInbox.Item>();long end=position;
            for(var row:rows.subList(0,Math.min(request.pageSize(),rows.size()))) {
                end=row.seq();
                if(row.body()==null || !authorized(row.delivery(),a.callerRef()))continue;
                items.add(new DeliveryInbox.Item(Long.toString(row.seq()),PacketJson.decDelivery(row.body()),inbox.readAt(mail,row.delivery())));
                inbox.issued(consumer.ref(),row.delivery());
            }
            String next=cursor!=null && !cursor.resync() && position==end && Instant.parse(cursor.expiry()).isAfter(clock.instant().plusSeconds(600))
                ?request.cursor():cursor(entry,consumer,position,end,false);
            return DeliveryInbox.Result.page(new DeliveryInbox.Page(consumer.ref(),items,next,rows.size()>request.pageSize()));
        });
    }
    void acknowledge(EntryConfig entry,DirectoryRepository.AdmissionRow a,DeliveryInbox.Acknowledgement ack,Runnable guard) {
        db.tx(c->{
            guard.run();var d=destination(entry,a.replyTargetRef(),null);requireRecipient(d,a);
            var consumer=inbox.consumer(ack.consumerRef());
            if(consumer==null || !consumer.mailbox().equals(mailbox(entry,d)))fail(ChannelErrorCode.PERMISSION_DENIED,"Consumer belongs to another identity");
            if("received".equals(ack.kind())) {
                var cursor=inbox.cursor(ack.throughCursor());requireCursor(cursor,consumer);
                if(cursor.resync() || !Instant.parse(cursor.expiry()).isAfter(clock.instant()) || cursor.baseline()!=consumer.baseline() || cursor.start()>consumer.received())
                    fail(ChannelErrorCode.INVALID_ARGUMENT,"Confirm only a contiguous issued inbox page");
                for(var item:inbox.items(consumer.mailbox(),cursor.start(),101)) {
                    if(item.seq()>cursor.end())break;
                    if(inbox.wasIssued(consumer.ref(),item.delivery()) && !authorized(item.delivery(),a.callerRef())) fail(ChannelErrorCode.PERMISSION_DENIED,"Page authorization has expired");
                }
                inbox.received(consumer,cursor.end());
            } else {
                for(String id:ack.deliveryIds()) {
                    var item=inbox.find(id);
                    if(item==null || !consumer.mailbox().equals(item.mailbox()) || !inbox.wasIssued(consumer.ref(),id) || !authorized(id,a.callerRef()))
                        fail(ChannelErrorCode.PERMISSION_DENIED,"Read acknowledgement is outside issued authorized content");
                    inbox.markRead(consumer.mailbox(),id,clock.instant().toString());
                }
            }
            return null;
        });
    }
    private DeliveryInbox.Result gap(EntryConfig e,InboxRepository.Consumer c,String reason) {
        long baseline=Math.max(c.received(),inbox.expiredThrough(c.mailbox(),clock.instant().toString()));
        return DeliveryInbox.Result.gap(reason,cursor(e,c,c.received(),baseline,true));
    }
    private String cursor(EntryConfig e,InboxRepository.Consumer c,long start,long end,boolean resync) {
        String token=Ids.next("icursor");
        inbox.cursor(token,new InboxRepository.Cursor(c.ref(),start,end,c.baseline(),resync,clock.instant().plusSeconds(setting(e,"cursorTtlSeconds",1800,60,86400)).toString()));return token;
    }
    private void requireCursor(InboxRepository.Cursor cursor,InboxRepository.Consumer c) {
        if(cursor==null || !cursor.consumer().equals(c.ref()))fail(ChannelErrorCode.INVALID_ARGUMENT,"Cursor does not belong to this consumer");
    }
    private void requireRecipient(DirectoryRepository.DestinationRow d,DirectoryRepository.AdmissionRow a) {
        if(!a.callerRef().equals(d.callerRef()) || !a.identitySubjectKind().equals(d.identitySubjectKind()) || !a.identitySubjectKey().equals(d.identitySubjectKey()))fail(ChannelErrorCode.PERMISSION_DENIED,"Inbox recipient changed");
    }
    private DirectoryRepository.DestinationRow destination(EntryConfig e,String ref,Long revision) {
        var d=directory.destination(ref);
        if(d==null || !e.entryRef().equals(d.entryRef()) || e.accountBindingEpoch()!=d.accountBindingEpoch() || (revision!=null && revision!=d.destinationRevision()) || d.callerRef()==null)
            fail(ChannelErrorCode.DESTINATION_STALE,"Original inbox identity is unavailable");
        return d;
    }
    private String mailbox(EntryConfig e,DirectoryRepository.DestinationRow d) {
        return Digests.sha256(String.join("\u0000",e.tenantRef(),e.workspaceRef(),e.entryRef(),Long.toString(e.accountBindingEpoch()),d.destinationRef(),Long.toString(d.destinationRevision())));
    }
    boolean authorized(String delivery,String caller) { return grants.currentDelivery(delivery,caller); }
    private DeliveryInbox.Receipt receipt(InboxRepository.Item i) { return new DeliveryInbox.Receipt(i.delivery(),i.receipt(),Long.toString(i.seq())); }
    private long setting(EntryConfig e,String name,long fallback,long min,long max) {
        long value=e.settings().path("inbox").path(name).asLong(fallback);
        if(value<min || value>max)fail(ChannelErrorCode.INVALID_ARGUMENT,"Invalid inbox retention/capacity policy");return value;
    }
    private static void fail(ChannelErrorCode c,String message) { throw new ChannelContractException(c,message); }
}
