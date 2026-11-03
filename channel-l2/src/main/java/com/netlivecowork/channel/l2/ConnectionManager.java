package com.netlivecowork.channel.l2;

import com.netlivecowork.channel.contract.adapter.ActivationResult;
import com.netlivecowork.channel.contract.adapter.AdapterDescriptor;
import com.netlivecowork.channel.contract.adapter.AdapterFactory;
import com.netlivecowork.channel.contract.adapter.AdapterInstance;
import com.netlivecowork.channel.contract.adapter.AuthPort;
import com.netlivecowork.channel.contract.adapter.ConnectResult;
import com.netlivecowork.channel.contract.adapter.ConnectionAttempt;
import com.netlivecowork.channel.contract.adapter.ConnectionEvent;
import com.netlivecowork.channel.contract.adapter.ConnectionPort;
import com.netlivecowork.channel.contract.adapter.EntryConfig;
import com.netlivecowork.channel.contract.adapter.LifecycleMode;
import com.netlivecowork.channel.contract.adapter.ProbeResult;
import com.netlivecowork.channel.contract.adapter.StopReason;
import com.netlivecowork.channel.contract.port.AdapterCatalog;
import com.netlivecowork.channel.contract.port.AdapterHost;
import com.netlivecowork.channel.contract.port.DeliveryPort;
import com.netlivecowork.channel.contract.port.DeliveryQueryPort;
import com.netlivecowork.channel.l2.store.EntryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry lifecycle and reconnect scheduling (INV-48, INV-49).
 *
 * <p>L2 owns connect intent, generation, backoff and health supervision; L1 only performs protocol
 * actions. Exactly one reconnect scheduler per connection: MANAGED is scheduled here, SDK_MANAGED is
 * left to the adapter which reports facts back.
 */
public final class ConnectionManager implements OutboxService.AdapterDeliveryAccess {

    private static final Logger log = LoggerFactory.getLogger(ConnectionManager.class);

    private final Clock clock;
    private final EntryRepository entries;
    private final AdapterCatalog catalog;
    private final Function<EntryConfig, AdapterHost> hosts;
    private final Map<String, Active> active = new ConcurrentHashMap<>();

    public ConnectionManager(Clock clock, EntryRepository entries, AdapterCatalog catalog,
                             Function<EntryConfig, AdapterHost> hosts) {
        this.clock = clock;
        this.entries = entries;
        this.catalog = catalog;
        this.hosts = hosts;
    }

    /**
     * generation 是宿主代次（同一实例内不变，供宿主设施栅栏）；
     * attemptGeneration 是连接尝试代次（每次重连递增，供旧连接回调用栅栏）。
     */
    private record Active(EntryConfig entry, AdapterDescriptor descriptor, AdapterInstance instance,
                          long generation, long attemptGeneration, long lastSequence, Instant lastProbe) {
    }

    // ---- lifecycle -------------------------------------------------------------------

    public synchronized void startAll() {
        for (EntryConfig entry : entries.enabledEntries()) {
            startEntry(entry.entryRef());
        }
    }

    public synchronized void startEntry(String entryRef) {
        EntryConfig entry = entries.find(entryRef);
        if (entry == null) {
            return;
        }
        if (!entry.enabled()) {
            saveRuntime(entryRef, entries.runtime(entryRef).generation(),
                EntryRepository.ConnectionState.DISABLED, null, 0, null);
            return;
        }
        if (active.containsKey(entryRef)) {
            return;
        }
        AdapterFactory factory = catalog.factory(entry.adapterType());
        AdapterDescriptor descriptor = catalog.descriptor(entry.adapterType());
        if (factory == null || descriptor == null) {
            saveRuntime(entryRef, entries.runtime(entryRef).generation(),
                EntryRepository.ConnectionState.BLOCKED, null, 0, null);
            log.warn("entry {} uses unknown adapter type {}", entryRef, entry.adapterType());
            return;
        }
        // 每次激活都是新代次：替换实例后，旧实例的宿主设施与回调凭代次识别为过期（INV-48、49）。
        long generation = entries.bumpGeneration(entryRef);
        // 尝试代次独立持久递增：实例替换后不会与旧实例的尝试代次重号。
        long attemptGeneration = entries.bumpAttemptGeneration(entryRef);
        saveRuntime(entryRef, generation, EntryRepository.ConnectionState.STARTING, null, 0, null);
        AdapterInstance instance;
        ActivationResult activation;
        try {
            instance = factory.create(entry.instanceConfig(entries.expectedExternalAccountKey(entryRef)),
                hosts.apply(entry));
            activation = instance.activate().toCompletableFuture()
                .get(entry.connectionPolicy().connectTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            saveRuntime(entryRef, generation, EntryRepository.ConnectionState.BLOCKED, null, 0, null);
            log.warn("entry {} failed to activate: {}", entryRef, e.toString());
            return;
        }
        active.put(entryRef, new Active(entry, descriptor, instance, generation, attemptGeneration, 0, null));
        verifyAccountBinding(entry, activation.externalAccountKey());
        ConnectionPort port = instance.connection();
        if (port == null) {
            saveRuntime(entryRef, generation, EntryRepository.ConnectionState.READY, clock.instant(), 0, null);
            return;
        }
        connect(entry, instance, generation, attemptGeneration);
    }

    private void connect(EntryConfig entry, AdapterInstance instance, long hostGeneration, long attemptGeneration) {
        ConnectionPort port = instance.connection();
        if (port == null) {
            return;
        }
        try {
            ConnectResult result = port.connect(new ConnectionAttempt(attemptGeneration,
                entry.connectionPolicy().connectTimeoutMs())).toCompletableFuture()
                .get(entry.connectionPolicy().connectTimeoutMs(), TimeUnit.MILLISECONDS);
            switch (result) {
                case ConnectResult.Connected connected -> {
                    verifyAccountBinding(entry, connected.externalAccountKey());
                    saveRuntime(entry.entryRef(), hostGeneration, EntryRepository.ConnectionState.ONLINE,
                        clock.instant(), 0, null);
                }
                case ConnectResult.Connecting ignored -> {
                    // An asynchronous adapter may report READY before connect() returns.
                    if (entries.runtime(entry.entryRef()).state() == EntryRepository.ConnectionState.STARTING)
                        saveRuntime(entry.entryRef(), hostGeneration, EntryRepository.ConnectionState.CONNECTING,
                            null, 0, null);
                }
            }
        } catch (Exception e) {
            scheduleReconnect(entry, hostGeneration, 1);
        }
    }

    public synchronized void stopEntry(String entryRef, StopReason reason) {
        Active current = active.remove(entryRef);
        if (current == null) {
            return;
        }
        // 先撤 Host 再清理：停止/失败期间旧宿主不得继续读写（v2.10 连接口径）。
        long revokedGeneration = entries.bumpGeneration(entryRef);
        try {
            ConnectionPort port = current.instance().connection();
            if (port != null) {
                port.disconnect(current.generation(), reason).toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            current.instance().deactivate(reason).toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("entry {} did not stop cleanly: {}", entryRef, e.toString());
        }
        saveRuntime(entryRef, revokedGeneration, EntryRepository.ConnectionState.DISABLED, null, 0, null);
    }

    public synchronized void stopAll(StopReason reason) {
        for (String entryRef : new ArrayList<>(active.keySet())) {
            stopEntry(entryRef, reason);
        }
    }

    /** Explicit reconnect: stop, then start again against the persisted generation counter. */
    public synchronized void reconnect(String entryRef) {
        if (active.containsKey(entryRef)) {
            stopEntry(entryRef, StopReason.RECONFIGURE);
        }
        startEntry(entryRef);
    }

    public synchronized void applyEnabled(String entryRef, boolean enabled) {
        if (enabled) {
            startEntry(entryRef);
        } else {
            stopEntry(entryRef, StopReason.DISABLED);
        }
    }

    // ---- facts and supervision -------------------------------------------------------

    public void handleEvent(String entryRef, ConnectionEvent event) {
        Active current = active.get(entryRef);
        if (current == null) {
            return;
        }
        if (event.generation() != current.attemptGeneration()) {
            // 旧连接的回调（上一次连接尝试的代次）必须失效，即使宿主实例仍然相同。
            log.debug("ignored stale connection event for {} attempt generation {}", entryRef,
                event.generation());
            return;
        }
        if (event.sequence() <= current.lastSequence()) {
            return;
        }
        active.put(entryRef, new Active(current.entry(), current.descriptor(), current.instance(),
            current.generation(), current.attemptGeneration(), event.sequence(), current.lastProbe()));
        switch (event) {
            case ConnectionEvent.Connected connected -> {
                verifyAccountBinding(current.entry(), connected.externalAccountKey());
                saveRuntime(entryRef, current.generation(), EntryRepository.ConnectionState.ONLINE,
                    clock.instant(), 0, null);
            }
            case ConnectionEvent.Heartbeat ignored -> saveRuntime(entryRef, current.generation(),
                EntryRepository.ConnectionState.ONLINE, clock.instant(), 0, null);
            case ConnectionEvent.Disconnected disconnected -> onDisconnected(current, disconnected.generation());
            case ConnectionEvent.Error error -> onDisconnected(current, error.generation());
            case ConnectionEvent.AuthRequired authRequired -> saveRuntime(entryRef, current.generation(),
                EntryRepository.ConnectionState.AUTH_REQUIRED, clock.instant(), 0, null);
        }
    }

    private void onDisconnected(Active current, long generation) {
        EntryRepository.Runtime runtime = entries.runtime(current.entry().entryRef());
        if (current.descriptor().capabilities().lifecycle() == LifecycleMode.SDK_MANAGED) {
            saveRuntime(current.entry().entryRef(), generation, EntryRepository.ConnectionState.CONNECTING,
                runtime.lastSeen(), runtime.retryCount(), null);
            return;
        }
        scheduleReconnect(current.entry(), generation, runtime.retryCount() + 1);
    }

    private void scheduleReconnect(EntryConfig entry, long generation, int attempt) {
        var policy = entry.connectionPolicy().reconnect();
        if (!policy.enabled() || (policy.maxAttempts() != null && attempt > policy.maxAttempts())) {
            saveRuntime(entry.entryRef(), generation, EntryRepository.ConnectionState.BLOCKED, null, attempt,
                null);
            return;
        }
        Instant next = clock.instant().plusMillis(policy.delayFor(attempt));
        saveRuntime(entry.entryRef(), generation, EntryRepository.ConnectionState.BACKOFF, null, attempt, next);
    }

    public synchronized void tick() {
        Instant now = clock.instant();
        for (EntryConfig entry : entries.all()) {
            Active current = active.get(entry.entryRef());
            if (current == null) {
                if (entry.enabled()) {
                    startEntry(entry.entryRef());
                }
                continue;
            }
            EntryRepository.Runtime runtime = entries.runtime(entry.entryRef());
            switch (runtime.state()) {
                case BACKOFF -> {
                    if (runtime.nextRetryAt() != null && !runtime.nextRetryAt().isAfter(now)) {
                        // 同一实例重连不换宿主代次，但连接尝试代次必须持久递增，旧连接回调随之失效。
                        long attemptGeneration = entries.bumpAttemptGeneration(entry.entryRef());
                        active.put(entry.entryRef(), new Active(current.entry(), current.descriptor(),
                            current.instance(), current.generation(), attemptGeneration, 0, current.lastProbe()));
                        saveRuntime(entry.entryRef(), current.generation(),
                            EntryRepository.ConnectionState.STARTING, null, 0, null);
                        connect(entry, current.instance(), current.generation(), attemptGeneration);
                    }
                }
                case ONLINE -> probeIfDue(entry, current, now);
                default -> {
                }
            }
        }
    }

    private void probeIfDue(EntryConfig entry, Active current, Instant now) {
        if (current.lastProbe() != null
            && current.lastProbe().plusMillis(entry.connectionPolicy().probeIntervalMs()).isAfter(now)) {
            return;
        }
        ConnectionPort port = current.instance().connection();
        if (port == null) {
            return;
        }
        try {
            ProbeResult result = port.probe(new ConnectionAttempt(current.attemptGeneration(),
                entry.connectionPolicy().probeTimeoutMs())).toCompletableFuture()
                .get(entry.connectionPolicy().probeTimeoutMs(), TimeUnit.MILLISECONDS);
            active.put(entry.entryRef(), new Active(current.entry(), current.descriptor(), current.instance(),
                current.generation(), current.attemptGeneration(), current.lastSequence(), now));
            if (result.status() == ProbeResult.Status.UNHEALTHY) {
                scheduleReconnect(entry, current.generation(),
                    entries.runtime(entry.entryRef()).retryCount() + 1);
            }
        } catch (Exception e) {
            scheduleReconnect(entry, current.generation(), entries.runtime(entry.entryRef()).retryCount() + 1);
        }
    }

    // ---- capability lookups ----------------------------------------------------------

    @Override
    public DeliveryPort deliveryPort(String entryRef) {
        Active current = active.get(entryRef);
        return current == null ? null : current.instance().delivery();
    }

    @Override
    public DeliveryQueryPort queryPort(String entryRef) {
        Active current = active.get(entryRef);
        return current == null ? null : current.instance().deliveryQuery();
    }

    @Override public com.netlivecowork.channel.contract.adapter.AdapterCapabilities.Handover handover(String entryRef) {
        Active current=active.get(entryRef);
        var delivery=current==null?null:current.descriptor().capabilities().delivery();
        return delivery==null?com.netlivecowork.channel.contract.adapter.AdapterCapabilities.Handover.PROVIDER:delivery.handover();
    }

    @Override
    public com.netlivecowork.channel.contract.adapter.AdapterCapabilities.Idempotency idempotency(
        String entryRef) {
        Active current = active.get(entryRef);
        if (current == null) {
            return com.netlivecowork.channel.contract.adapter.AdapterCapabilities.Idempotency.NONE;
        }
        var delivery = current.descriptor().capabilities().delivery();
        return delivery == null
            ? com.netlivecowork.channel.contract.adapter.AdapterCapabilities.Idempotency.NONE
            : delivery.idempotency();
    }

    public AdapterInstance instance(String entryRef) {
        Active current = active.get(entryRef);
        return current == null ? null : current.instance();
    }

    public AuthPort authPort(String entryRef) {
        Active current = active.get(entryRef);
        return current == null ? null : current.instance().auth();
    }

    public AdapterDescriptor descriptor(String entryRef) {
        Active current = active.get(entryRef);
        return current == null ? null : current.descriptor();
    }

    public ProbeResult probe(String entryRef) {
        Active current = active.get(entryRef);
        if (current == null || current.instance().connection() == null) {
            return ProbeResult.unknown("entry is not connected");
        }
        try {
            return current.instance().connection()
                .probe(new ConnectionAttempt(current.generation(),
                    current.entry().connectionPolicy().probeTimeoutMs()))
                .toCompletableFuture().get(current.entry().connectionPolicy().probeTimeoutMs(),
                    TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return ProbeResult.unknown("probe did not complete");
        }
    }

    public long generation(String entryRef) {
        Active current = active.get(entryRef);
        return current == null ? entries.runtime(entryRef).generation() : current.generation();
    }

    public EntryRepository.Runtime runtime(String entryRef) {
        return entries.runtime(entryRef);
    }

    public List<String> activeEntries() {
        return new ArrayList<>(active.keySet());
    }

    private void verifyAccountBinding(EntryConfig entry, String externalAccountKey) {
        if (externalAccountKey == null || externalAccountKey.isBlank()) {
            return;
        }
        String expected = entries.expectedExternalAccountKey(entry.entryRef());
        if (expected != null && !expected.isBlank() && !expected.equals(externalAccountKey)) {
            stopEntry(entry.entryRef(), StopReason.DISABLED);
            saveRuntime(entry.entryRef(), generation(entry.entryRef()),
                EntryRepository.ConnectionState.BLOCKED, null, 0, null);
            log.warn("entry {} account mismatch; business is blocked", entry.entryRef());
            return;
        }
        entries.recordObservedAccount(entry.entryRef(), externalAccountKey);
    }

    private void saveRuntime(String entryRef, long generation, EntryRepository.ConnectionState state,
                             Instant lastSeen, int retryCount, Instant nextRetryAt) {
        EntryRepository.Runtime current = entries.runtime(entryRef);
        entries.saveRuntime(new EntryRepository.Runtime(entryRef, generation, current.attemptGeneration(),
            state, lastSeen, retryCount, nextRetryAt, "l2-connection-manager"));
    }
}
