package com.netlivecowork.channel.contract;

/** 处置登记结果：只有 RECORDED/DUPLICATE 才允许确认平台游标（S69）。 */
public enum DispositionResult {
    /** 首次登记成功（权威记录已落盘）。 */
    RECORDED,
    /** 同键同内容已登记：幂等复用，不重复写。 */
    DUPLICATE,
    /** 同键异内容：隔离，不覆盖原处置。 */
    CONFLICT,
    /** 落盘失败：调用方不得确认游标，必须保留重投。 */
    FAILED
}
