package com.netlivecowork.channel.l1.fake;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netlivecowork.channel.contract.ChannelDelivery;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.ChannelOperation;
import com.netlivecowork.channel.contract.ChannelResponse;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.DeliveryAttempt;
import com.netlivecowork.channel.contract.DeliveryReceipt;
import com.netlivecowork.channel.contract.IngressRequest;
import com.netlivecowork.channel.contract.IngressResolution;
import com.netlivecowork.channel.contract.VerifiedIngress;
import com.netlivecowork.channel.contract.adapter.ActivationResult;
import com.netlivecowork.channel.contract.adapter.AdapterInstance;
import com.netlivecowork.channel.contract.adapter.AdapterInstanceConfig;
import com.netlivecowork.channel.contract.adapter.StopReason;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.port.AdapterHost;
import com.netlivecowork.channel.contract.port.DeliveryPort;
import com.netlivecowork.channel.l1.render.PlainTextRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 测试入口实例：按序记录已受理的投递，并提供与真实入口相同的入站路径
 * （resolve + submit），使 L2 集成测试不需要平台协议。
 */
public final class FakeAdapterInstance implements AdapterInstance, DeliveryPort {

    private final AdapterInstanceConfig config;
    private final AdapterHost host;
    private final List<ChannelDelivery> deliveries = new ArrayList<>();
    private final Object lifecycle = new Object();

    private volatile State state = State.NEW;

    public FakeAdapterInstance(AdapterInstanceConfig config, AdapterHost host) {
        this.config = Objects.requireNonNull(config, "config");
        this.host = Objects.requireNonNull(host, "host");
    }

    private enum State {
        NEW, ACTIVE, STOPPED
    }

    @Override
    public String entryRef() {
        return config.entryRef();
    }

    @Override
    public DeliveryPort delivery() {
        return this;
    }

    @Override
    public CompletionStage<ActivationResult> activate() {
        synchronized (lifecycle) {
            if (state == State.STOPPED) {
                return CompletableFuture.failedFuture(new IllegalStateException("fake instance is stopped"));
            }
            state = State.ACTIVE;
        }
        return CompletableFuture.completedFuture(
            new ActivationResult(FakeAdapterFactory.capabilities(), "fake:" + config.entryRef()));
    }

    @Override
    public CompletionStage<Void> deactivate(StopReason reason) {
        synchronized (lifecycle) {
            state = State.STOPPED;
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<DeliveryReceipt> deliver(ChannelDelivery delivery, DeliveryAttempt attempt) {
        String deliveryId = delivery.deliveryId();
        if (state != State.ACTIVE) {
            return CompletableFuture.completedFuture(new DeliveryReceipt.Failed(deliveryId,
                ChannelErrorCode.UNAVAILABLE.error("fake instance is not active")));
        }
        if (!delivery.actions().isEmpty()) {
            return CompletableFuture.completedFuture(new DeliveryReceipt.Failed(deliveryId,
                ChannelErrorCode.UNSUPPORTED_CONTENT.error("fake entry cannot express interaction actions")));
        }
        try {
            PlainTextRenderer.body(delivery.packet());
        } catch (PlainTextRenderer.UnsupportedContent e) {
            return CompletableFuture.completedFuture(new DeliveryReceipt.Failed(deliveryId,
                ChannelErrorCode.UNSUPPORTED_CONTENT.error(e.getMessage())));
        }
        synchronized (deliveries) {
            deliveries.add(delivery);
        }
        return CompletableFuture.completedFuture(new DeliveryReceipt.Accepted(deliveryId, null));
    }

    /** 已受理投递的按序快照。 */
    public List<ChannelDelivery> deliveries() {
        synchronized (deliveries) {
            return List.copyOf(deliveries);
        }
    }

    public void reset() {
        synchronized (deliveries) {
            deliveries.clear();
        }
    }

    /** 与真实入口相同的入站路径：令牌已在入口层验证，这里只提供调用者引用。 */
    public ChannelResponse submit(String requestId, String callerRef, String text) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is required");
        }
        if (callerRef == null || callerRef.isBlank()) {
            throw new IllegalArgumentException("callerRef is required");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        ObjectNode protocolData = Json.obj().put("conversation", callerRef);
        VerifiedIngress verified = new VerifiedIngress(requestId, "fake:" + config.entryRef(),
            new VerifiedIngress.Subject(VerifiedIngress.SubjectKind.USER, callerRef),
            new VerifiedIngress.ExternalScope("fake", callerRef),
            new VerifiedIngress.ExternalReplyTarget(VerifiedIngress.Audience.PERSONAL, callerRef, protocolData),
            requestId, null);
        IngressResolution resolution = host.ingress().resolve(verified).toCompletableFuture().join();
        IngressRequest request = new IngressRequest(IngressRequest.SUPPORTED_VERSION, requestId,
            resolution.identity(), resolution.routing(),
            new ChannelOperation.InputSubmit(null, ChannelOperation.Interpretation.COMMAND_AWARE,
                List.of(new ContentPart.Text("p1", text)), null));
        return host.ingress().submit(resolution.admissionRef(), request).toCompletableFuture().join();
    }
}
