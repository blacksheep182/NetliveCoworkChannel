package com.netlivecowork.channel.contract.port;

import com.netlivecowork.channel.contract.DeliveryReceipt;
import java.util.concurrent.CompletionStage;

/** 可选的平台投递查询：仅在平台具备查询能力时声明，用于 unknown 核对（INV-35）。 */
public interface DeliveryQueryPort {

    CompletionStage<DeliveryReceipt> queryDelivery(String deliveryId);
}
