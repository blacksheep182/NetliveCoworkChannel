package com.netlivecowork.channel.contract.adapter;

import java.util.concurrent.CompletionStage;

/** 主动连接端口。connect/disconnect/probe 由 L2 串行协调，Adapter 不自行维护重连策略。 */
public interface ConnectionPort {

    CompletionStage<ConnectResult> connect(ConnectionAttempt attempt);

    CompletionStage<Void> disconnect(long generation, StopReason reason);

    CompletionStage<ProbeResult> probe(ConnectionAttempt attempt);
}
