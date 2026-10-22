package com.netlivecowork.channel;

import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import static com.netlivecowork.channel.ChannelConfig.Connection;

/** Spring discovers adapter beans; configured connections must resolve before any worker starts. */
@Component
public class ChannelAdapters {
    private final ChannelConfig config;
    private final Map<String,ChannelAdapter> adapters=new HashMap<>();

    public ChannelAdapters(ChannelConfig config,List<ChannelAdapter> adapters) {
        this.config=config;
        for (var adapter:adapters) {
            if (adapter.type()==null || adapter.type().isBlank() || this.adapters.putIfAbsent(adapter.type(),adapter)!=null)
                throw new IllegalArgumentException("Missing or duplicate adapter type: "+adapter.type());
        }
        for (var connection:config.connections()) get(connection).validate(connection);
    }
    public Connection connection(String id) {
        return config.connections().stream().filter(c->c.id().equals(id)).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Unknown connection: "+id));
    }
    public ChannelAdapter get(Connection connection) {
        var adapter=adapters.get(connection.type());
        if (adapter==null) throw new IllegalArgumentException("Unsupported adapter: "+connection.type());
        return adapter;
    }
    @EventListener(ApplicationReadyEvent.class) public void start() {
        for (var connection:config.connections()) get(connection).start(connection);
    }
    @PreDestroy public void stop() { adapters.values().forEach(ChannelAdapter::stop); }
}
