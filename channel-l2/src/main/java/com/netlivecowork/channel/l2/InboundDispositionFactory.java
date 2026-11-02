package com.netlivecowork.channel.l2;

import com.netlivecowork.channel.contract.port.InboundDispositionPort;

/** 按接入实例与账号代次绑定入站处置设施；实例隔离由 L2 负责（INV-48）。 */
public interface InboundDispositionFactory {

    InboundDispositionPort forEntry(String entryRef, long accountBindingEpoch);
}
