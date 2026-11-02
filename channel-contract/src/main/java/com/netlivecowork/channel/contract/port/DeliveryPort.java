package com.netlivecowork.channel.contract.port;

import com.netlivecowork.channel.contract.ChannelDelivery;
import com.netlivecowork.channel.contract.DeliveryAttempt;
import com.netlivecowork.channel.contract.DeliveryReceipt;
import java.util.concurrent.CompletionStage;

/** L2 → L1 投递端口。Adapter 按 packet.type 渲染，平台受限时显式降级（INV-38）。 */
public interface DeliveryPort {

    CompletionStage<DeliveryReceipt> deliver(ChannelDelivery delivery, DeliveryAttempt attempt);
}
