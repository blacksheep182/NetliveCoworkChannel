package com.netlivecowork.channel.l1.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.ChannelOperation;
import com.netlivecowork.channel.contract.ChannelResponse;
import com.netlivecowork.channel.contract.FileReadResult;
import com.netlivecowork.channel.contract.IngressRequest;
import com.netlivecowork.channel.contract.IngressResolution;
import com.netlivecowork.channel.contract.ProtocolStateEntry;
import com.netlivecowork.channel.contract.ResolvedDestination;
import com.netlivecowork.channel.contract.SecretValue;
import com.netlivecowork.channel.contract.VerifiedIngress;
import com.netlivecowork.channel.contract.adapter.ConnectionEvent;
import com.netlivecowork.channel.contract.adapter.ConnectionEventSink;
import com.netlivecowork.channel.contract.port.AdapterHost;
import com.netlivecowork.channel.contract.port.DestinationResolver;
import com.netlivecowork.channel.contract.port.FileAccess;
import com.netlivecowork.channel.contract.port.IngressPort;
import com.netlivecowork.channel.contract.port.ProtocolStateStore;
import com.netlivecowork.channel.contract.port.SecretAccess;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** 手写 AdapterHost 桩：记录 resolve/submit/事件，内存版协议状态与目的地解析。 */
public final class TestHost {

    public final String entryRef;
    public final List<VerifiedIngress> resolved = Collections.synchronizedList(new ArrayList<>());
    public final List<VerifiedIngress> ownerBindings = Collections.synchronizedList(new ArrayList<>());
    public final List<String> ownerBindingTokens = Collections.synchronizedList(new ArrayList<>());
    public final List<IngressRequest> submitted = Collections.synchronizedList(new ArrayList<>());
    public final List<ConnectionEvent> events = Collections.synchronizedList(new ArrayList<>());
    public final Map<String, JsonNode> destinations = new ConcurrentHashMap<>();
    public final Map<String, SecretValue> secrets = new ConcurrentHashMap<>();
    public final TestDispositions dispositions;

    /** 入站受理结果，默认持久受理。 */
    public volatile Function<IngressRequest, ChannelResponse> submitResponse = request ->
        new ChannelResponse.Accepted(request.requestId(), "operation-" + request.requestId(), null);

    /** resolve 阶段失败注入：非 null 时 resolve 返回该业务异常（验证确定性拒绝的分类）。 */
    public volatile ChannelContractException resolveFailure;
    public final List<String> lookedUp = new ArrayList<>();
    public volatile Function<String, ChannelResponse> lookupResponse = requestId ->
        new ChannelResponse.Accepted(requestId, "operation-" + requestId, null);

    private final Map<String, ProtocolStateEntry> states = new ConcurrentHashMap<>();
    private final AtomicLong revisions = new AtomicLong();
    private final AdapterHost host;
    public final Map<String,com.netlivecowork.channel.contract.ChannelDelivery> inboxDeliveries=new java.util.LinkedHashMap<>();
    private final Map<String,com.netlivecowork.channel.contract.DeliveryInbox.Receipt> inboxReceipts=new java.util.LinkedHashMap<>();

    private final class RecordingInbox implements com.netlivecowork.channel.contract.port.DurableInboxPort {
        public CompletionStage<com.netlivecowork.channel.contract.DeliveryInbox.Receipt> append(String id) {
            return CompletableFuture.completedFuture(inboxReceipts.computeIfAbsent(id,key->new com.netlivecowork.channel.contract.DeliveryInbox.Receipt(key,"receipt-"+key,Long.toString(inboxReceipts.size()+1))));
        }
        public CompletionStage<com.netlivecowork.channel.contract.DeliveryReceipt> queryReceipt(String id) {
            var r=inboxReceipts.get(id);return CompletableFuture.completedFuture(r==null?new com.netlivecowork.channel.contract.DeliveryReceipt.Unknown(id,ChannelErrorCode.OUTCOME_UNKNOWN.error("unknown")):new com.netlivecowork.channel.contract.DeliveryReceipt.Accepted(id,r.receiptRef()));
        }
        public CompletionStage<com.netlivecowork.channel.contract.DeliveryInbox.Result> read(String admission,com.netlivecowork.channel.contract.DeliveryInbox.Request request) {
            String caller=resolved.getLast().externalSubject().key();
            var items=inboxReceipts.values().stream().filter(r->inboxDeliveries.containsKey(r.deliveryId())).map(r->new com.netlivecowork.channel.contract.DeliveryInbox.Item(r.sequence(),inboxDeliveries.get(r.deliveryId()),null))
                .filter(item->caller.equals(destinations.get(item.delivery().routing().destinationRef()).path("conversation").asText())).toList();
            return CompletableFuture.completedFuture(com.netlivecowork.channel.contract.DeliveryInbox.Result.page(new com.netlivecowork.channel.contract.DeliveryInbox.Page("consumer",items,"cursor",false)));
        }
        public CompletionStage<Void> acknowledge(String admission,com.netlivecowork.channel.contract.DeliveryInbox.Acknowledgement ack) { return CompletableFuture.completedFuture(null); }
    }

    public TestHost(String entryRef) {
        this.entryRef = entryRef;
        this.dispositions = new TestDispositions(entryRef, 1L);
        this.host = new AdapterHost(new RecordingIngress(), this::recordEvent, new RecordingSecrets(),
            new RecordingState(), new RecordingDestinations(), new UnsupportedFiles(), dispositions,new RecordingInbox());
    }

    public AdapterHost host() {
        return host;
    }

    public JsonNode stateValue(String key) {
        ProtocolStateEntry entry = states.get(key);
        return entry == null ? null : entry.value();
    }

    public ChannelOperation operation(int index) {
        return submitted.get(index).operation();
    }

    private CompletionStage<Void> recordEvent(ConnectionEvent event) {
        events.add(event);
        return CompletableFuture.completedFuture(null);
    }

    private final class RecordingIngress implements IngressPort {
        @Override
        public CompletionStage<Boolean> completeOwnerWeixinBinding(VerifiedIngress input, String token) {
            ownerBindings.add(input);
            ownerBindingTokens.add(token);
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<IngressResolution> resolve(VerifiedIngress input) {
            if (resolveFailure != null) {
                return CompletableFuture.failedFuture(resolveFailure);
            }
            resolved.add(input);
            String scope = input.externalScope() == null ? null : input.externalScope().key();
            String reply = input.externalReplyTarget() == null ? null : input.externalReplyTarget().key();
            IngressResolution resolution = new IngressResolution("admission-" + input.requestId(),
                new IngressRequest.Identity(entryRef, input.externalSubject().key()),
                new IngressRequest.Routing(scope, reply));
            return CompletableFuture.completedFuture(resolution);
        }

        @Override
        public CompletionStage<ChannelResponse> submit(String admissionRef, IngressRequest request) {
            submitted.add(request);
            return CompletableFuture.completedFuture(submitResponse.apply(request));
        }

        @Override
        public CompletionStage<com.netlivecowork.channel.contract.SessionAccess.View> getSessionAccess(String admissionRef) {
            return CompletableFuture.completedFuture(new com.netlivecowork.channel.contract.SessionAccess.View(0,"verified",List.of()));
        }
        @Override
        public CompletionStage<com.netlivecowork.channel.contract.SessionAccess.Saved> saveSessionAccess(String admissionRef,com.netlivecowork.channel.contract.SessionAccess.Save request) {
            return CompletableFuture.completedFuture(new com.netlivecowork.channel.contract.SessionAccess.Saved(request.requestId(),1,request.targetRefs()));
        }
        @Override
        public CompletionStage<com.netlivecowork.channel.contract.SessionAccess.Saved> lookupSessionAccess(String admissionRef,String requestId) {
            lookedUp.add(requestId);return CompletableFuture.completedFuture(new com.netlivecowork.channel.contract.SessionAccess.Saved(requestId,1,List.of()));
        }
        @Override
        public CompletionStage<ChannelResponse> lookup(String admissionRef, String requestId) {
            lookedUp.add(requestId);
            return CompletableFuture.completedFuture(lookupResponse.apply(requestId));
        }

        @Override
        public CompletionStage<ChannelOperation> resolveAction(String admissionRef, String actionRef) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("resolveAction"));
        }
    }

    private final class RecordingSecrets implements SecretAccess {

        @Override
        public CompletionStage<SecretValue> read(String name) {
            SecretValue value = secrets.get(name);
            return value == null
                ? CompletableFuture.failedFuture(new ChannelContractException(ChannelErrorCode.AUTH_REQUIRED,
                    "secret is not bound: " + name))
                : CompletableFuture.completedFuture(value);
        }

        @Override
        public CompletionStage<Void> replace(String name, long expectedRevision, String value) {
            secrets.put(name, new SecretValue(value, expectedRevision + 1, null));
            return CompletableFuture.completedFuture(null);
        }
    }

    private final class RecordingState implements ProtocolStateStore {

        @Override
        public CompletionStage<ProtocolStateEntry> get(String key) {
            return CompletableFuture.completedFuture(states.get(key));
        }

        @Override
        public CompletionStage<Long> put(String key, Long expectedRevision, JsonNode value) {
            ProtocolStateEntry current = states.get(key);
            if (expectedRevision != null && (current == null || current.revision() != expectedRevision)) {
                return CompletableFuture.failedFuture(new IllegalStateException("state revision conflict: " + key));
            }
            long revision = revisions.incrementAndGet();
            states.put(key, new ProtocolStateEntry(revision, value));
            return CompletableFuture.completedFuture(revision);
        }

        @Override
        public CompletionStage<Void> remove(String key, long expectedRevision) {
            states.remove(key);
            return CompletableFuture.completedFuture(null);
        }
    }

    private final class RecordingDestinations implements DestinationResolver {

        @Override
        public CompletionStage<ResolvedDestination> resolve(String destinationRef, long expectedRevision) {
            JsonNode protocolData = destinations.get(destinationRef);
            if (protocolData == null) {
                return CompletableFuture.failedFuture(new ChannelContractException(ChannelErrorCode.DESTINATION_STALE,
                    "destination is not registered: " + destinationRef));
            }
            return CompletableFuture.completedFuture(
                new ResolvedDestination(destinationRef, expectedRevision, protocolData));
        }
    }

    private static final class UnsupportedFiles implements FileAccess {

        @Override
        public CompletionStage<FileReadResult> read(String fileRef, String deliveryId) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("files"));
        }
    }
}
