package com.netlivecowork.channel.contract.port;

import com.netlivecowork.channel.contract.IntakeAck;
import com.netlivecowork.channel.contract.ReceiptPacket;
import com.netlivecowork.channel.contract.VerifiedBackendEvent;
import java.util.concurrent.CompletionStage;

/**
 * L3 → L2 转交端口。L2 持久接管（或保存待关联记录）后才确认；确认丢失时 L3 重传原记录，
 * L2 去重（INV-31）。
 */
public interface ApplicationIntakePort {

    CompletionStage<IntakeAck> receiveEvent(VerifiedBackendEvent event);

    /** 稍后取得的后端确认/核对事实，按 packetId 持久去重接管。 */
    CompletionStage<IntakeAck> receiveReceipt(ReceiptPacket receipt);
}
