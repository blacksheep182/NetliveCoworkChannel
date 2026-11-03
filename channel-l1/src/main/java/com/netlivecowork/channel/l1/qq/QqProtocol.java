package com.netlivecowork.channel.l1.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.json.Json;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/** QQ Open Platform HTTP calls. Credentials never enter entry settings or logs. */
final class QqProtocol {
    static final URI DEFAULT_API = URI.create("https://api.sgroup.qq.com");
    static final URI DEFAULT_TOKEN = URI.create("https://bots.qq.com/app/getAppAccessToken");
    record Failure(ChannelErrorCode code, boolean unknown, String message) {}
    static final class PlatformFailure extends RuntimeException {
        final Failure failure;
        PlatformFailure(ChannelErrorCode code, boolean unknown, String message) {
            super(message); failure = new Failure(code, unknown, message);
        }
    }

    private final HttpClient http;
    private final String appId;
    private final Path secretFile;
    private final URI api;
    private final URI tokenEndpoint;
    private String accessToken;
    private Instant tokenExpires = Instant.EPOCH;

    QqProtocol(HttpClient http, String appId, Path secretFile, URI api, URI tokenEndpoint) {
        this.http = http; this.appId = appId; this.secretFile = secretFile;
        this.api = requireSecure(api); this.tokenEndpoint = requireSecure(tokenEndpoint);
    }

    static URI requireSecure(URI uri) {
        if (uri == null || uri.getHost() == null ||
            !("https".equalsIgnoreCase(uri.getScheme()) ||
              "http".equalsIgnoreCase(uri.getScheme()) &&
                  ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()))))
            throw new IllegalArgumentException("QQ endpoint must use HTTPS (HTTP only for loopback fixtures)");
        return uri;
    }

    static URI requireOfficialApi(URI uri) {
        requireSecure(uri);
        String host=uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (!("api.sgroup.qq.com".equals(host) || "sandbox.api.sgroup.qq.com".equals(host)
            || "api.bot.qq.com".equals(host) || "localhost".equals(host) || "127.0.0.1".equals(host)))
            throw new IllegalArgumentException("QQ API host must be an official QQ endpoint");
        return uri;
    }

    synchronized String token() {
        if (accessToken != null && Instant.now().isBefore(tokenExpires.minusSeconds(60))) return accessToken;
        String secret;
        try {
            JsonNode file = Json.mapper().readTree(Files.readString(secretFile));
            secret = file.path("clientSecret").asText("");
        } catch (IOException | RuntimeException e) {
            throw new PlatformFailure(ChannelErrorCode.AUTH_REQUIRED, false, "QQ client secret file is unavailable");
        }
        if (secret.isBlank()) throw new PlatformFailure(ChannelErrorCode.AUTH_REQUIRED, false, "QQ client secret is missing");
        var body = Json.obj().put("appId", appId).put("clientSecret", secret);
        JsonNode result = call("POST", tokenEndpoint, null, Json.write(body), Duration.ofSeconds(10), false);
        String next = result.path("access_token").asText("");
        long seconds = result.path("expires_in").asLong(0);
        if (next.isBlank() || seconds <= 0)
            throw new PlatformFailure(ChannelErrorCode.AUTH_REQUIRED, false, "QQ access token response is invalid");
        accessToken = next; tokenExpires = Instant.now().plusSeconds(seconds);
        return next;
    }

    synchronized void invalidateToken() { accessToken = null; tokenExpires = Instant.EPOCH; }
    HttpClient client() { return http; }

    URI gateway() {
        JsonNode result = call("GET", api.resolve("/gateway"), token(), null, Duration.ofSeconds(10), false);
        URI uri = URI.create(result.path("url").asText(""));
        if (!("wss".equalsIgnoreCase(uri.getScheme()) ||
            "ws".equalsIgnoreCase(uri.getScheme()) &&
                ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()))))
            throw new PlatformFailure(ChannelErrorCode.UNAVAILABLE, false, "QQ gateway URL is invalid");
        return uri;
    }

    String send(boolean group, String target, String messageId, int sequence, String text, Duration timeout) {
        String path = group ? "/v2/groups/" : "/v2/users/";
        URI uri = api.resolve(path + URLEncoder.encode(target, StandardCharsets.UTF_8) + "/messages");
        var body = Json.obj().put("content", text).put("msg_type", 0)
            .put("msg_id", messageId).put("msg_seq", sequence);
        JsonNode result = call("POST", uri, token(), Json.write(body), timeout, true);
        String id = result.path("id").asText("");
        if (id.isBlank()) throw new PlatformFailure(ChannelErrorCode.OUTCOME_UNKNOWN, true,
            "QQ accepted a message without a receipt ID");
        return id;
    }

    private JsonNode call(String method, URI uri, String token, String body, Duration timeout, boolean sending) {
        var builder = HttpRequest.newBuilder(uri).timeout(timeout)
            .header("Accept", "application/json");
        if (token != null) builder.header("Authorization", "QQBot " + token);
        HttpRequest request = body == null ? builder.GET().build() :
            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                invalidateToken();
                throw new PlatformFailure(ChannelErrorCode.AUTH_REQUIRED, false, "QQ rejected the bot credential");
            }
            if (response.statusCode() == 429)
                throw new PlatformFailure(ChannelErrorCode.RATE_LIMITED, false, "QQ message rate limit reached");
            if (response.statusCode() >= 400)
                throw new PlatformFailure(response.statusCode() >= 500 ? (sending ? ChannelErrorCode.OUTCOME_UNKNOWN : ChannelErrorCode.UNAVAILABLE)
                    : ChannelErrorCode.PERMISSION_DENIED, sending && response.statusCode() >= 500,
                    "QQ API rejected " + method + " with HTTP " + response.statusCode());
            try { return Json.read(response.body()); }
            catch (RuntimeException e) {
                throw new PlatformFailure(sending ? ChannelErrorCode.OUTCOME_UNKNOWN : ChannelErrorCode.UNAVAILABLE,
                    sending, "QQ API response is invalid");
            }
        } catch (IOException e) {
            throw new PlatformFailure(sending ? ChannelErrorCode.OUTCOME_UNKNOWN : ChannelErrorCode.UNAVAILABLE,
                sending, "QQ API request did not return a receipt");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformFailure(sending ? ChannelErrorCode.OUTCOME_UNKNOWN : ChannelErrorCode.UNAVAILABLE,
                sending, "QQ API request was interrupted");
        }
    }
}
