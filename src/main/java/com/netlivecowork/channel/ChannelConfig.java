package com.netlivecowork.channel;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.HashSet;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("channel")
public record ChannelConfig(String adminToken, Path dataFile, List<Backend> backends,
                            List<Connection> connections, List<Binding> bindings) {
    public ChannelConfig {
        if (adminToken == null || adminToken.length() < 32)
            throw new IllegalArgumentException("CHANNEL_ADMIN_TOKEN must contain at least 32 characters");
        dataFile = dataFile == null ? Path.of("data/channel.db") : dataFile;
        backends = backends == null ? List.of() : List.copyOf(backends);
        connections = connections == null ? List.of() : List.copyOf(connections);
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        unique(backends.stream().map(Backend::id).toList());
        unique(connections.stream().map(Connection::id).toList());
        unique(bindings.stream().map(Binding::id).toList());
        var identities = new HashSet<String>();
        for (var binding : bindings) {
            if (!identities.add(binding.connectionId + "\0" + binding.externalUserId + "\0" + binding.conversationId))
                throw new IllegalArgumentException("Duplicate external identity binding");
            if (connections.stream().noneMatch(c -> c.id.equals(binding.connectionId)))
                throw new IllegalArgumentException("Unknown connection: " + binding.connectionId);
            if (binding.targets.isEmpty()) throw new IllegalArgumentException("Binding needs an authorized target");
            for (var target : binding.targets) {
                if (backends.stream().noneMatch(b -> b.id.equals(target.backendId)))
                    throw new IllegalArgumentException("Unknown backend: " + target.backendId);
                required(target.workspaceRef); required(target.sessionId);
            }
        }
    }
    private static void required(String value) {
        if (value == null || value.isBlank() || value.length()>128) throw new IllegalArgumentException("Configuration identity must contain 1-128 characters");
    }
    private static void unique(List<String> ids) {
        var seen = new HashSet<String>();
        for (String id : ids) { required(id); if (!seen.add(id)) throw new IllegalArgumentException("Duplicate id: " + id); }
    }
    public record Backend(String id, URI url, String token) {
        public Backend {
            required(id);
            if (url == null || url.getHost() == null || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null
                || !List.of("http", "https").contains(url.getScheme()))
                throw new IllegalArgumentException("Backend URL must be a configured HTTP(S) origin");
            if (!"https".equals(url.getScheme()) && !List.of("localhost", "127.0.0.1", "[::1]", "::1").contains(url.getHost()))
                throw new IllegalArgumentException("Non-local backends require HTTPS");
        }
    }
    public record Connection(String id, String type, URI url, Path secretFile) {
        public Connection {
            required(id); required(type);
        }
    }
    public record Target(String backendId, String workspaceRef, String sessionId) {}
    public record Binding(String id, String connectionId, String externalUserId, String conversationId,
                          List<Target> targets) {
        public Binding {
            required(id); required(connectionId); required(externalUserId); required(conversationId);
            targets = targets == null ? List.of() : List.copyOf(targets);
        }
    }
}
