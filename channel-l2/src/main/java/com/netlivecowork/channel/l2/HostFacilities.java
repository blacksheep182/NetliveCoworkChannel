package com.netlivecowork.channel.l2;

import com.fasterxml.jackson.databind.JsonNode;
import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.ChannelOperation;
import com.netlivecowork.channel.contract.ChannelResponse;
import com.netlivecowork.channel.contract.IngressRequest;
import com.netlivecowork.channel.contract.IngressResolution;
import com.netlivecowork.channel.contract.ProtocolStateEntry;
import com.netlivecowork.channel.contract.ResolvedDestination;
import com.netlivecowork.channel.contract.SecretValue;
import com.netlivecowork.channel.contract.VerifiedIngress;
import com.netlivecowork.channel.contract.adapter.ConnectionEvent;
import com.netlivecowork.channel.contract.adapter.ConnectionEventSink;
import com.netlivecowork.channel.contract.adapter.EntryConfig;
import com.netlivecowork.channel.contract.port.AdapterHost;
import com.netlivecowork.channel.contract.port.BackendAccessPort;
import com.netlivecowork.channel.contract.port.DestinationResolver;
import com.netlivecowork.channel.contract.port.FileAccess;
import com.netlivecowork.channel.contract.port.IngressPort;
import com.netlivecowork.channel.contract.port.ProtocolStateStore;
import com.netlivecowork.channel.contract.port.SecretAccess;
import com.netlivecowork.channel.infra.Ids;
import com.netlivecowork.channel.l2.store.DeliveryRepository;
import com.netlivecowork.channel.l2.store.DirectoryRepository;
import com.netlivecowork.channel.l2.store.EntryRepository;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * Instance-bound restricted facilities. Adapters only reach L2 public capability through these ports and
 * never touch business storage or L3 directly (INV-47). Every call is scoped to the current entry, its
 * generation and the adapter version.
 */
public final class HostFacilities {

    /** Connection fact report; generation and ordering checks live in ConnectionManager (INV-49). */
    public interface EventSink {
        void report(String entryRef, ConnectionEvent event);
    }

    private final Clock clock;
    private final AdmissionService admission;
    private final EventSink events;
    private final EntryRepository entries;
    private final DirectoryRepository directory;
    private final DeliveryRepository deliveries;
    private final BackendAccessPort backend;
    private final SecretBox secretBox;
    private final InboundDispositionFactory dispositions;
    private final UserSessionAccessService sessionAccess;
    private final SessionHistoryService history;
    private final DurableInboxService inbox;
    private final OwnerWeixinBindingService ownerWeixinBindings;

    public HostFacilities(Clock clock, AdmissionService admission, EventSink events, EntryRepository entries,
                          DirectoryRepository directory, DeliveryRepository deliveries,
                          BackendAccessPort backend, SecretBox secretBox,
                          InboundDispositionFactory dispositions, UserSessionAccessService sessionAccess,
                          SessionHistoryService history, DurableInboxService inbox,
                          OwnerWeixinBindingService ownerWeixinBindings) {
        this.clock = clock;
        this.admission = admission;
        this.events = events;
        this.entries = entries;
        this.directory = directory;
        this.deliveries = deliveries;
        this.backend = backend;
        this.secretBox = secretBox;
        this.dispositions = dispositions;
        this.sessionAccess = sessionAccess;
        this.history = history;
        this.inbox = inbox;
        this.ownerWeixinBindings = ownerWeixinBindings;
    }

    public AdapterHost hostFor(EntryConfig entry, String adapterVersion) {
        long hostGeneration = generation(entry.entryRef());
        return new AdapterHost(ingress(entry, hostGeneration), eventSink(entry), secretAccess(entry),
            protocolState(entry, adapterVersion, hostGeneration), destinationResolver(entry), fileAccess(entry),
            dispositions.forEntry(entry.entryRef(), entry.accountBindingEpoch()), inboxPort(entry,hostGeneration));
    }

    private com.netlivecowork.channel.contract.port.DurableInboxPort inboxPort(EntryConfig entry,long generation) {
        Runnable guard=()->{
            requireHostCurrent(entry,generation);
            var current=entries.find(entry.entryRef());
            if(current==null || !current.enabled() || current.accountBindingEpoch()!=entry.accountBindingEpoch())
                throw new ChannelContractException(ChannelErrorCode.AUTH_REQUIRED,"Original inbox host has been revoked");
        };
        return new com.netlivecowork.channel.contract.port.DurableInboxPort() {
            public CompletionStage<Void> verifyStorage() { return guard(()->{guard.run();inbox.verify(entry);return null;}); }
            public CompletionStage<com.netlivecowork.channel.contract.DeliveryInbox.Receipt> append(String id) { return guard(()->inbox.append(entry,id,guard)); }
            public CompletionStage<com.netlivecowork.channel.contract.DeliveryReceipt> queryReceipt(String id) { return guard(()->inbox.query(entry,id,guard)); }
            public CompletionStage<com.netlivecowork.channel.contract.DeliveryInbox.Result> read(String ref,com.netlivecowork.channel.contract.DeliveryInbox.Request request) {
                return guard(()->inbox.read(entry,admission.requireAdmission(entry,ref),request,()->{guard.run();admission.requireAdmission(entry,ref);}));
            }
            public CompletionStage<Void> acknowledge(String ref,com.netlivecowork.channel.contract.DeliveryInbox.Acknowledgement ack) {
                return guard(()->{inbox.acknowledge(entry,admission.requireAdmission(entry,ref),ack,()->{guard.run();admission.requireAdmission(entry,ref);});return null;});
            }
        };
    }

    /** 替换实例后旧宿主一律失效：不得再签发 admission、写游标或其他实例限定状态（INV-48、49）。 */
    private void requireHostCurrent(EntryConfig entry, long hostGeneration) {
        if (generation(entry.entryRef()) != hostGeneration) {
            throw new ChannelContractException(ChannelErrorCode.AUTH_REQUIRED,
                "adapter host belongs to a replaced connection generation");
        }
    }

    /** Restricted credential access used by management-side connection tests. */
    public SecretAccess secretsFor(EntryConfig entry) {
        return secretAccess(entry);
    }

    public Clock clock() {
        return clock;
    }

    private IngressPort ingress(EntryConfig entry, long hostGeneration) {
        return new IngressPort() {
            @Override
            public CompletionStage<IngressResolution> resolve(VerifiedIngress input) {
                return guard(() -> {
                    requireHostCurrent(entry, hostGeneration);
                    return admission.resolve(entry, input);
                });
            }

            @Override
            public CompletionStage<ChannelResponse> submit(String admissionRef, IngressRequest request) {
                return guard(() -> {
                    requireHostCurrent(entry, hostGeneration);
                    return admission.submit(entry, admissionRef, request);
                });
            }

            @Override
            public CompletionStage<ChannelResponse> lookup(String admissionRef, String requestId) {
                return guard(() -> {
                    requireHostCurrent(entry, hostGeneration);
                    return admission.lookup(entry, admissionRef, requestId);
                });
            }

            @Override
            public CompletionStage<Boolean> completeOwnerWeixinBinding(VerifiedIngress input, String token) {
                return guard(() -> {
                    requireHostCurrent(entry, hostGeneration);
                    return ownerWeixinBindings.complete(entry, input, token);
                });
            }

            private Runnable managementGuard(String ref) {
                return ()->{requireHostCurrent(entry,hostGeneration);admission.managementCall(entry,ref);};
            }
            @Override
            public CompletionStage<com.netlivecowork.channel.contract.SessionHistory.Page> queryHistory(
                    String ref, com.netlivecowork.channel.contract.SessionHistory.Request request) {
                return CompletableFuture.supplyAsync(()->history.query(entry,admission.managementCall(entry,ref),
                    request,managementGuard(ref)));
            }
            @Override
            public CompletionStage<com.netlivecowork.channel.contract.SessionAccess.View> getSessionAccess(String ref) {
                return CompletableFuture.supplyAsync(()->sessionAccess.get(admission.managementCall(entry,ref),managementGuard(ref)));
            }
            @Override
            public CompletionStage<com.netlivecowork.channel.contract.SessionAccess.Saved> saveSessionAccess(String ref,com.netlivecowork.channel.contract.SessionAccess.Save request) {
                return CompletableFuture.supplyAsync(()->sessionAccess.save(admission.managementCall(entry,ref),request,managementGuard(ref)));
            }
            @Override
            public CompletionStage<com.netlivecowork.channel.contract.SessionAccess.Saved> lookupSessionAccess(String ref,String requestId) {
                return CompletableFuture.supplyAsync(()->sessionAccess.lookup(admission.managementCall(entry,ref),requestId,managementGuard(ref)));
            }

            @Override
            public CompletionStage<ChannelOperation> resolveAction(String admissionRef, String actionRef) {
                return guard(() -> {
                    requireHostCurrent(entry, hostGeneration);
                    return admission.resolveAction(entry, admissionRef, actionRef);
                });
            }
        };
    }

    private ConnectionEventSink eventSink(EntryConfig entry) {
        return event -> {
            events.report(entry.entryRef(), event);
            return CompletableFuture.completedFuture(null);
        };
    }

    private SecretAccess secretAccess(EntryConfig entry) {
        return new SecretAccess() {
            @Override
            public CompletionStage<SecretValue> read(String name) {
                return CompletableFuture.supplyAsync(() -> {
                    EntryRepository.SecretRow row = entries.secretByPurpose(entry.entryRef(), name);
                    if (row == null) {
                        throw new ChannelContractException(ChannelErrorCode.AUTH_REQUIRED,
                            "credential is not provisioned for this entry");
                    }
                    requireKeyBox();
                    return new SecretValue(secretBox.decrypt(row.cipher(), row.keyId()), row.revision(), null);
                });
            }

            @Override
            public CompletionStage<Void> replace(String name, long expectedRevision, String value) {
                return CompletableFuture.runAsync(() -> {
                    requireKeyBox();
                    EntryRepository.SecretRow existing = entries.secretByPurpose(entry.entryRef(), name);
                    long currentRevision = existing == null ? 0L : existing.revision();
                    if (currentRevision != expectedRevision) {
                        throw new ChannelContractException(ChannelErrorCode.INTERNAL_ERROR,
                            "credential revision conflict");
                    }
                    String secretRef = existing == null ? "sec-" + Ids.random(20) : existing.secretRef();
                    entries.saveSecret(secretRef, entry.entryRef(), name, secretBox.encrypt(value),
                        expectedRevision + 1, secretBox.keyId());
                });
            }

            private void requireKeyBox() {
                if (secretBox == null) {
                    throw new ChannelContractException(ChannelErrorCode.AUTH_REQUIRED,
                        "credential protection key is not configured");
                }
            }
        };
    }

    private ProtocolStateStore protocolState(EntryConfig entry, String adapterVersion, long hostGeneration) {
        return new ProtocolStateStore() {
            @Override
            public CompletionStage<ProtocolStateEntry> get(String key) {
                return CompletableFuture.supplyAsync(() -> {
                    requireHostCurrent(entry, hostGeneration);
                    return entries.state(entry.entryRef(), generation(entry.entryRef()), adapterVersion, key);
                });
            }

            @Override
            public CompletionStage<Long> put(String key, Long expectedRevision, JsonNode value) {
                return CompletableFuture.supplyAsync(() -> {
                    requireHostCurrent(entry, hostGeneration);
                    return entries.putState(entry.entryRef(), generation(entry.entryRef()), adapterVersion,
                        key, expectedRevision, value);
                });
            }

            @Override
            public CompletionStage<Void> remove(String key, long expectedRevision) {
                return CompletableFuture.runAsync(() -> {
                    requireHostCurrent(entry, hostGeneration);
                    entries.removeState(entry.entryRef(), generation(entry.entryRef()), adapterVersion, key,
                        expectedRevision);
                });
            }
        };
    }

    private DestinationResolver destinationResolver(EntryConfig entry) {
        return (destinationRef, expectedRevision) -> CompletableFuture.supplyAsync(() -> {
            DirectoryRepository.DestinationRow row = directory.destination(destinationRef);
            if (row == null || !row.entryRef().equals(entry.entryRef())) {
                throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,
                    "destination does not belong to this entry");
            }
            if (row.destinationRevision() != expectedRevision) {
                throw new ChannelContractException(ChannelErrorCode.DESTINATION_STALE,
                    "destination revision changed");
            }
            return new ResolvedDestination(destinationRef, row.destinationRevision(), row.protocolData());
        });
    }

    private FileAccess fileAccess(EntryConfig entry) {
        return (fileRef, deliveryId) -> CompletableFuture.supplyAsync(() -> {
            DeliveryRepository.DeliveryRow delivery = deliveries.delivery(deliveryId);
            if (delivery == null || !delivery.entryRef().equals(entry.entryRef())) {
                throw new ChannelContractException(ChannelErrorCode.PERMISSION_DENIED,
                    "delivery does not belong to this entry");
            }
            DirectoryRepository.FileRow file = directory.file(fileRef);
            if (file == null) {
                throw new ChannelContractException(ChannelErrorCode.REQUEST_NOT_FOUND, "unknown file");
            }
            try {
                return backend.readFile(fileRef, deliveryId).toCompletableFuture().join();
            } catch (RuntimeException e) {
                throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE,
                    "file bytes are not available from the backend");
            }
        });
    }

    private long generation(String entryRef) {
        return entries.runtime(entryRef).generation();
    }

    private <T> CompletionStage<T> guard(Supplier<T> work) {
        try {
            return CompletableFuture.completedFuture(work.get());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
