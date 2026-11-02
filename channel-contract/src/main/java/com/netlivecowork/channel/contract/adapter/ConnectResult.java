package com.netlivecowork.channel.contract.adapter;

/** connect 结果：已连接（含实际账号）或异步连接中，后者通过 ConnectionEventSink 上报事实。 */
public sealed interface ConnectResult permits ConnectResult.Connected, ConnectResult.Connecting {

    record Connected(String externalAccountKey) implements ConnectResult {
        public Connected {
            if (externalAccountKey == null || externalAccountKey.isBlank()) {
                throw new IllegalArgumentException("externalAccountKey is required");
            }
        }
    }

    record Connecting() implements ConnectResult {
    }
}
