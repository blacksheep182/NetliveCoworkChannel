package com.netlivecowork.channel.contract;

/**
 * 派发阶段：确定未发送 / 发送结果未知 / 后端确认接收（l3-routing-and-recovery.md §5）。
 */
public enum DispatchStatus {
    /** 有可核验的派发事实证明没有任何发送尝试。 */
    NOT_SENT,
    /** 写入前已登记尝试，结果不明；必须按原 requestKey 核对，不重复执行。 */
    OUTCOME_UNKNOWN,
    /** 后端确切受理。 */
    AGENT_ACCEPTED
}
