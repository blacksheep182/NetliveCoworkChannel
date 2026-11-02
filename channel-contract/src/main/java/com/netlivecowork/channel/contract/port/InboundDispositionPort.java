package com.netlivecowork.channel.contract.port;

import com.netlivecowork.channel.contract.DispositionResult;
import com.netlivecowork.channel.contract.InboundDisposition;
import java.util.concurrent.CompletionStage;

/**
 * L1 → L2 的入站处置设施：按完整来源键登记确定性拒绝，供重投时复用原处置（S69～S72）。
 * 实例与账号代次在调用方绑定时固定，不接受客户端自报归属。
 */
public interface InboundDispositionPort {

    /** 查找原处置；不存在返回 null，过期记录以 {@code expired=true} 返回而不是当作不存在。 */
    CompletionStage<InboundDisposition> find(String sourceEventId);

    /** 登记确定性拒绝；只有 RECORDED/DUPLICATE 表示可安全确认平台游标。 */
    CompletionStage<DispositionResult> recordRejected(String sourceEventId, String contentDigest, String code);

    /** 保留边界之外的缺口：明确标为待人工核对，不能当作“从未处理”。 */
    CompletionStage<Void> markGap(String sourceEventId, String reason);
}
