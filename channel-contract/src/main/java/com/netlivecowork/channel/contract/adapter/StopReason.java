package com.netlivecowork.channel.contract.adapter;

/** 停用原因。停用只清理本实例资源，不取消后端任务或订阅（INV-24）。 */
public enum StopReason {
    DISABLED, RECONFIGURE, SHUTDOWN, LEASE_LOST
}
