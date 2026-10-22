package com.netlivecowork.channel;

import org.springframework.stereotype.Component;
import static com.netlivecowork.channel.ChannelConfig.Connection;

/** Test delivery is captured by the shared outbox; no platform request is made. */
@Component
public class FakeAdapter implements ChannelAdapter {
    @Override public String type() { return "fake"; }
    @Override public String state(Connection connection) { return "connected"; }
    @Override public void send(Connection connection,String conversation,String context,String id,String text) {}
}
