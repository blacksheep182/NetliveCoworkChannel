package com.netlivecowork.channel.contract.adapter;

import java.util.concurrent.CompletionStage;

/** L1 → L2 的连接事实上报端口。 */
public interface ConnectionEventSink {

    CompletionStage<Void> report(ConnectionEvent event);
}
