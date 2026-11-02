package com.netlivecowork.channel.contract.adapter;

import com.netlivecowork.channel.contract.ChannelError;

/**
 * 连接协议事实（L1 → L2）。只报事实，不做策略；旧代次回调一律拒绝（INV-49）。
 */
public sealed interface ConnectionEvent permits ConnectionEvent.Connected, ConnectionEvent.Disconnected,
    ConnectionEvent.Heartbeat, ConnectionEvent.AuthRequired, ConnectionEvent.Error {

    long generation();

    long sequence();

    record Connected(long generation, long sequence, String externalAccountKey) implements ConnectionEvent {
    }

    record Disconnected(long generation, long sequence, ChannelError error) implements ConnectionEvent {
    }

    record Heartbeat(long generation, long sequence) implements ConnectionEvent {
    }

    record AuthRequired(long generation, long sequence, ChannelError error) implements ConnectionEvent {
    }

    record Error(long generation, long sequence, ChannelError error) implements ConnectionEvent {
    }

    default ChannelError error() {
        return switch (this) {
            case Disconnected d -> d.error();
            case AuthRequired a -> a.error();
            case Error e -> e.error();
            case Connected c -> null;
            case Heartbeat h -> null;
        };
    }
}
