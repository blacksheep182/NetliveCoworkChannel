package com.netlivecowork.channel.contract.port;

import com.netlivecowork.channel.contract.adapter.ConnectionEventSink;

/**
 * 实例绑定的受限设施集合。Adapter 只经这些端口使用 L2 公共能力，不直接访问业务存储或 L3
 * （INV-47）。未声明能力的调用返回 UNSUPPORTED_CAPABILITY。
 */
public record AdapterHost(IngressPort ingress, ConnectionEventSink events, SecretAccess secrets,
                          ProtocolStateStore protocolState, DestinationResolver destinations, FileAccess files,
                          InboundDispositionPort dispositions, DurableInboxPort inbox) {

    public AdapterHost(IngressPort ingress, ConnectionEventSink events, SecretAccess secrets,
                       ProtocolStateStore protocolState, DestinationResolver destinations, FileAccess files,
                       InboundDispositionPort dispositions) {
        this(ingress, events, secrets, protocolState, destinations, files, dispositions, null);
    }

    public AdapterHost {
        if (ingress == null || events == null || secrets == null || protocolState == null
            || destinations == null || files == null || dispositions == null) {
            throw new IllegalArgumentException("AdapterHost requires all facilities");
        }
    }
}
