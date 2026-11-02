package com.netlivecowork.channel.contract.adapter;

/** 运行方式。重连调度唯一：managed 由 L2 调度，sdkManaged 由 SDK 维护（INV-48）。 */
public enum LifecycleMode {
    MANAGED, SDK_MANAGED, PASSIVE;
}
