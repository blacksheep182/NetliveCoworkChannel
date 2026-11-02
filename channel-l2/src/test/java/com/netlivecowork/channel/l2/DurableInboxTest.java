package com.netlivecowork.channel.l2;

import static org.junit.jupiter.api.Assertions.*;
import com.netlivecowork.channel.contract.*;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.json.PacketJson;
import com.netlivecowork.channel.l2.store.DeliveryRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class DurableInboxTest {
    static final String CLIENT="browser-client-00000001", OTHER="browser-client-00000002";
    static VerifiedIngress verified(String id,String user) {
        return new VerifiedIngress(id,"acct-1",new VerifiedIngress.Subject(VerifiedIngress.SubjectKind.USER,user),
            new VerifiedIngress.ExternalScope("test",user),new VerifiedIngress.ExternalReplyTarget(VerifiedIngress.Audience.PERSONAL,user,Json.obj().put("conversation",user)),id,null);
    }
    static class Fixture implements AutoCloseable {
        final TestModule t=new TestModule(); final com.netlivecowork.channel.contract.adapter.EntryConfig entry=t.createEntry();
        final com.netlivecowork.channel.contract.port.AdapterHost host=t.adapter.host;
        final IngressResolution a=host.ingress().resolve(verified("input","alice")).toCompletableFuture().join();
        final ChannelResponse.Accepted accepted=(ChannelResponse.Accepted)host.ingress().submit(a.admissionRef(),new IngressRequest("1","input",a.identity(),a.routing(),
            new ChannelOperation.InputSubmit("target-1",ChannelOperation.Interpretation.LITERAL,List.of(new ContentPart.Text("p1","完成分析报告")),null))).toCompletableFuture().join();
        ChannelDelivery message(String id,String backendMessage) {
            t.module.intake().receiveEvent(new VerifiedBackendEvent("be-"+id,new EventSource("resource-1",id),accepted.operationRef(),"target-1",null,EventType.MESSAGE_READY,t.clock.instant(),null,
                new AgentEventPayload.MessageReady(List.of(new NormalizedContentPart.Text("p1","答案 "+id,ContentPart.Text.Format.PLAIN)),backendMessage,null))).toCompletableFuture().join();
            var event=t.module.events().find("resource-1",id);assertNotNull(event.packetId(),event.lastError());
            var row=t.module.deliveries().claimable(1000).stream().filter(r->r.packetId().equals(event.packetId())).findFirst().orElseThrow();
            assertTrue(t.module.deliveries().markSending(row,"test-worker",t.clock.instant().plusSeconds(30)));
            return PacketJson.decDelivery(row.deliveryJson());
        }
        DeliveryInbox.Receipt append(ChannelDelivery d) { return host.inbox().append(d.deliveryId()).toCompletableFuture().join(); }
        DeliveryInbox.Result read(String key,String cursor) { return host.inbox().read(a.admissionRef(),new DeliveryInbox.Request(key,cursor,100)).toCompletableFuture().join(); }
        void ack(DeliveryInbox.Acknowledgement ack) { host.inbox().acknowledge(a.admissionRef(),ack).toCompletableFuture().join(); }
        public void close() { t.module.close();t.db.close(); }
    }
    @Test void custodyIsIdempotentAndClientProgressIsIndependent() {
        try(var f=new Fixture()) {
            var d=f.message("one","body-one");var r=f.append(d);assertEquals(r,f.append(d));
            assertInstanceOf(DeliveryReceipt.Accepted.class,f.host.inbox().queryReceipt(d.deliveryId()).toCompletableFuture().join());
            var page=f.read(CLIENT,null).page();assertEquals(1,page.items().size());assertEquals(d,page.items().getFirst().delivery());
            var peer=f.read(OTHER,null).page();
            assertThrows(RuntimeException.class,()->f.ack(new DeliveryInbox.Acknowledgement(peer.consumerRef(),"received",page.nextCursor(),null)));
            f.ack(new DeliveryInbox.Acknowledgement(page.consumerRef(),"received",page.nextCursor(),null));
            f.ack(new DeliveryInbox.Acknowledgement(page.consumerRef(),"received",page.nextCursor(),null));
            assertTrue(f.read(CLIENT,null).page().items().isEmpty());assertEquals(1,f.read(OTHER,null).page().items().size());
            f.ack(new DeliveryInbox.Acknowledgement(page.consumerRef(),"read",null,List.of(d.deliveryId())));
            assertNotNull(f.read(OTHER,null).page().items().getFirst().readAt());
            assertEquals(1,f.t.db.queryOne("SELECT COUNT(*) FROM l2_delivery_inbox",rs->rs.getInt(1)).intValue());
        }
    }
    @Test void commitWithLostReceiptReconcilesEvenAfterDestinationRebind() {
        try(var f=new Fixture()) {
            var d=f.message("lost","body-lost");var r=f.append(d);
            assertTrue(f.t.module.deliveries().markTerminal(d.deliveryId(),DeliveryRepository.DeliveryStatus.UNKNOWN,"response lost","test-worker",1));
            f.t.module.directory().rebindIdentity(TestModule.ENTRY,1,"USER","alice","different-person");
            assertThrows(RuntimeException.class,()->f.read(CLIENT,null));
            f.t.module.tick();var row=f.t.module.deliveries().delivery(d.deliveryId());
            assertEquals(DeliveryRepository.DeliveryStatus.ACCEPTED,row.status());assertEquals(r.receiptRef(),row.receiptRef());
            assertEquals(1,f.t.db.queryOne("SELECT COUNT(*) FROM l2_delivery_inbox",rs->rs.getInt(1)).intValue());
        }
    }
    @Test void restartKeepsContentsAndRevokesTheOldHost() {
        try(var f=new Fixture()) {
            var d=f.message("restart","body-restart");var r=f.append(d);f.t.module.close();
            var restarted=new L2Module(f.t.db,f.t.clock,TestModule.settings(),f.t.catalog,f.t.backend,null);
            try {
                restarted.start();var host=f.t.adapter.host;
                assertThrows(RuntimeException.class,()->f.host.inbox().append(d.deliveryId()).toCompletableFuture().join());
                var a=host.ingress().resolve(verified("reconnect","alice")).toCompletableFuture().join();
                var page=host.inbox().read(a.admissionRef(),new DeliveryInbox.Request(CLIENT,null,20)).toCompletableFuture().join().page();
                assertEquals(r.sequence(),page.items().getFirst().sequence());assertEquals(d.deliveryId(),page.items().getFirst().delivery().deliveryId());
            } finally { restarted.close(); }
        }
    }
    @Test void moreThanTwoHundredMessagesArePagedWithoutEviction() {
        try(var f=new Fixture()) {
            for(int i=0;i<205;i++)f.append(f.message("bulk-"+i,"body-"+i));
            String cursor=null;int total=0;boolean more;
            do {var page=f.read(CLIENT,cursor).page();total+=page.items().size();cursor=page.nextCursor();more=page.hasMore();}while(more);
            assertEquals(205,total);
        }
    }
    @Test void retentionCreatesExplicitGapButPreservesCustodyTombstone() {
        try(var f=new Fixture()) {
            var d=f.message("expired","body-expired");var r=f.append(d);
            f.t.clock.advanceMillis(2592001_000L);
            var a=f.host.ingress().resolve(verified("after-retention","alice")).toCompletableFuture().join();
            var gap=f.host.inbox().read(a.admissionRef(),new DeliveryInbox.Request(CLIENT,null,20)).toCompletableFuture().join();
            assertEquals("gap",gap.status());assertEquals("retention",gap.reason());
            var result=f.host.inbox().read(a.admissionRef(),new DeliveryInbox.Request(CLIENT,gap.resyncCursor(),20)).toCompletableFuture().join();
            assertTrue(result.page().items().isEmpty());
            assertEquals(r.receiptRef(),((DeliveryReceipt.Accepted)f.host.inbox().queryReceipt(d.deliveryId()).toCompletableFuture().join()).receiptRef());
        }
    }
    @Test void completionBeforeMessagesKeepsStableReferencesAndOneNotice() {
        try(var f=new Fixture()) {
            f.t.module.intake().receiveEvent(new VerifiedBackendEvent("be-complete",new EventSource("resource-1","complete"),f.accepted.operationRef(),"target-1",null,EventType.TASK_COMPLETED,f.t.clock.instant(),null,
                new AgentEventPayload.TaskCompleted("task-long",3,List.of("result-one","result-two"),List.of()))).toCompletableFuture().join();
            var row=f.t.module.events().find("resource-1","complete");assertNotNull(row.packetId(),row.lastError());
            var completed=PacketJson.decEvent(row.packetJson());var refs=((EventPayload.TaskCompleted)completed.payload()).resultMessageRefs();
            assertEquals(2,refs.size());var a=f.message("result-event-one","result-one");var b=f.message("result-event-two","result-two");
            assertEquals(refs,List.of(((EventPayload.MessageReady)a.packet().payload()).message().messageRef(),((EventPayload.MessageReady)b.packet().payload()).message().messageRef()));
            assertFalse("[]".equals(f.t.module.requests().message(refs.getFirst()).contentJson()));
            var delivery=f.t.module.deliveries().claimable(100).stream().filter(d->d.packetId().equals(completed.packetId())).findFirst().orElseThrow();
            var notice=PacketJson.decDelivery(delivery.deliveryJson());assertEquals("task_completed",notice.presentation().notification().kind());
            assertEquals("request_reply",notice.presentation().notification().origin());assertEquals("完成分析报告",notice.presentation().source().taskSummary());
            assertEquals(notice,PacketJson.decDelivery(Json.write(PacketJson.enc(notice))));
        }
    }

    @Test void rollbackDoesNotCreateCustodyAndSameKeyCanRecover() {
        try(var f=new Fixture()) {
            var d=f.message("before-commit","body-before");
            f.t.db.ddl("CREATE TRIGGER fail_inbox BEFORE INSERT ON l2_delivery_inbox BEGIN SELECT RAISE(ABORT,'injected storage failure'); END");
            assertThrows(RuntimeException.class,()->f.append(d));
            assertInstanceOf(DeliveryReceipt.Unknown.class,f.host.inbox().queryReceipt(d.deliveryId()).toCompletableFuture().join());
            f.t.db.ddl("DROP TRIGGER fail_inbox");assertNotNull(f.append(d).receiptRef());
        }
    }

    @Test void issuedPageIsRequiredAndAcknowledgementCannotSkipAConsumerPrefix() {
        try(var f=new Fixture()) {
            var first=f.message("prefix-one","prefix-one");f.append(first);
            var second=f.message("prefix-two","prefix-two");f.append(second);
            var one=f.host.inbox().read(f.a.admissionRef(),new DeliveryInbox.Request(CLIENT,null,1)).toCompletableFuture().join().page();
            assertThrows(RuntimeException.class,()->f.ack(new DeliveryInbox.Acknowledgement(one.consumerRef(),"read",null,List.of(second.deliveryId()))));
            var two=f.host.inbox().read(f.a.admissionRef(),new DeliveryInbox.Request(CLIENT,one.nextCursor(),1)).toCompletableFuture().join().page();
            assertThrows(RuntimeException.class,()->f.ack(new DeliveryInbox.Acknowledgement(one.consumerRef(),"received",two.nextCursor(),null)));
            f.ack(new DeliveryInbox.Acknowledgement(one.consumerRef(),"received",one.nextCursor(),null));
            f.ack(new DeliveryInbox.Acknowledgement(one.consumerRef(),"received",two.nextCursor(),null));
            assertTrue(f.read(CLIENT,null).page().items().isEmpty());
        }
    }

    @Test void memoryCannotClaimReliableStorageAndHandoverCannotChangeForAnExistingDelivery() {
        try(var f=new Fixture();var memory=com.netlivecowork.channel.infra.Db.memory()) {
            var service=new DurableInboxService(memory,f.t.clock,f.t.module.deliveries(),f.t.module.directory(),f.t.module.grants());
            assertThrows(ChannelContractException.class,service::requirePersistent);
            var d=f.message("mode","mode");
            assertTrue(f.t.module.deliveries().freezeHandover(d.deliveryId(),"DURABLE_INBOX"));
            assertFalse(f.t.module.deliveries().freezeHandover(d.deliveryId(),"PROVIDER"));
            assertTrue(f.t.module.deliveries().markTerminal(d.deliveryId(),DeliveryRepository.DeliveryStatus.UNKNOWN,"uncertain","test-worker",1));
            assertTrue(f.t.module.deliveries().retryInboxUnknown(d.deliveryId()));
            assertEquals(DeliveryRepository.DeliveryStatus.RETRY,f.t.module.deliveries().delivery(d.deliveryId()).status());
        }
    }
}
