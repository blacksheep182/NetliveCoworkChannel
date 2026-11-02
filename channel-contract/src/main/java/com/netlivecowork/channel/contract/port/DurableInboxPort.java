package com.netlivecowork.channel.contract.port;

import com.netlivecowork.channel.contract.DeliveryInbox;
import com.netlivecowork.channel.contract.DeliveryReceipt;
import java.util.concurrent.CompletionStage;

/** Instance-bound L2 facility. append/queryReceipt are never client HTTP operations. */
public interface DurableInboxPort {
    default CompletionStage<Void> verifyStorage() { return java.util.concurrent.CompletableFuture.completedFuture(null); }
    CompletionStage<DeliveryInbox.Receipt> append(String deliveryId);
    CompletionStage<DeliveryReceipt> queryReceipt(String deliveryId);
    CompletionStage<DeliveryInbox.Result> read(String admissionRef, DeliveryInbox.Request request);
    CompletionStage<Void> acknowledge(String admissionRef, DeliveryInbox.Acknowledgement acknowledgement);
}
