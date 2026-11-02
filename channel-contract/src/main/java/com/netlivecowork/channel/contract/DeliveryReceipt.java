package com.netlivecowork.channel.contract;

/**
 * 平台投递结果，与业务指令回执分开。accepted 仅表示平台受理；failed 仅表示确定未受理或明确拒绝；
 * 回执丢失返回 unknown（INV-35）。
 */
public sealed interface DeliveryReceipt permits DeliveryReceipt.Accepted, DeliveryReceipt.Failed,
    DeliveryReceipt.Unknown {

    String deliveryId();

    record Accepted(String deliveryId, String receiptRef) implements DeliveryReceipt {
        public Accepted {
            Producer.requireText(deliveryId, "deliveryId");
            receiptRef = receiptRef == null || receiptRef.isBlank() ? null : receiptRef;
        }
    }

    record Failed(String deliveryId, ChannelError error) implements DeliveryReceipt {
        public Failed {
            Producer.requireText(deliveryId, "deliveryId");
            if (error == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "failed requires error");
            }
        }
    }

    record Unknown(String deliveryId, ChannelError error) implements DeliveryReceipt {
        public Unknown {
            Producer.requireText(deliveryId, "deliveryId");
            if (error == null) {
                throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "unknown requires error");
            }
        }
    }
}
