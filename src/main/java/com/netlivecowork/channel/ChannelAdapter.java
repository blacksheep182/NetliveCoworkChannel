package com.netlivecowork.channel;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import static com.netlivecowork.channel.ChannelConfig.Connection;

/** Platform boundary. Inbound messages enter ChannelService.receive only after platform verification. */
public interface ChannelAdapter {
    String type();
    default void validate(Connection connection) {}
    default void start(Connection connection) {}
    default void stop() {}
    String state(Connection connection);
    void send(Connection connection,String conversation,String context,String id,String text);

    default JsonNode beginAuth(Connection connection) {
        throw new IllegalArgumentException("This adapter does not support interactive authentication");
    }
    default JsonNode authStatus(Connection connection) {
        throw new IllegalArgumentException("This adapter does not support interactive authentication");
    }

    /** Public endpoint: verify the platform signature before reading or admitting messages. */
    default Object webhook(Connection connection,HttpServletRequest request) {
        throw new IllegalArgumentException("This adapter does not support webhooks");
    }
}
