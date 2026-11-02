package com.netlivecowork.channel.l1.weixin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.json.Json;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;

/**
 * iLink 协议细节：端点、请求头、QR 授权与凭据文件。这里只执行协议动作，
 * 不决定连接与重连策略（INV-48）；凭据只在本实例的受限文件里读写（INV-52）。
 */
final class WeixinProtocol {

    static final String DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com";
    static final Duration LONG_POLL_TIMEOUT = Duration.ofSeconds(70);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final String APP_ID = "bot";
    private static final String CLIENT_VERSION = "65536";
    private static final String CHANNEL_VERSION = "0x00000100";

    private final HttpClient http;
    private final Path secretFile;
    private final String settingsBaseUrl;
    private final ObjectMapper json = Json.mapper();
    private volatile Secret secret;

    WeixinProtocol(HttpClient http, Path secretFile, String settingsBaseUrl) {
        this.http = http;
        this.secretFile = secretFile;
        this.settingsBaseUrl = settingsBaseUrl;
    }

    /** 凭据文件内容：bot_token 只留在此文件，不进入状态、事件或响应（INV-52）。 */
    record Secret(String botToken, String botId, String userId, String baseUrl) {

        static final Secret EMPTY = new Secret(null, null, null, null);

        boolean hasToken() {
            return botToken != null && !botToken.isBlank();
        }
    }

    /** 平台协议失败：code 用于错误码，outcomeUnknown 决定 unknown 还是 failed（INV-35）。 */
    static final class PlatformFailure extends RuntimeException {

        private final ChannelErrorCode code;
        private final boolean outcomeUnknown;

        PlatformFailure(ChannelErrorCode code, boolean outcomeUnknown, String message) {
            super(message);
            this.code = code;
            this.outcomeUnknown = outcomeUnknown;
        }

        ChannelErrorCode code() {
            return code;
        }

        boolean outcomeUnknown() {
            return outcomeUnknown;
        }
    }

    Secret secret() {
        Secret current = secret;
        return current == null ? refreshSecret() : current;
    }

    /** 每次轮询与投递前重新读取，授权完成或换绑后立即生效。 */
    Secret refreshSecret() {
        Secret current = readSecret();
        secret = current;
        return current;
    }

    private Secret readSecret() {
        try {
            if (!Files.exists(secretFile)) {
                return Secret.EMPTY;
            }
            JsonNode node = json.readTree(secretFile.toFile());
            return new Secret(text(node, "bot_token"), text(node, "ilink_bot_id"),
                text(node, "ilink_user_id"), text(node, "baseurl"));
        } catch (IOException | RuntimeException e) {
            throw new PlatformFailure(ChannelErrorCode.INTERNAL_ERROR, false, "weixin credential file is unreadable");
        }
    }

    /** 原子写入：临时文件 + 权限收紧 + 原子替换，避免读到半个凭据文件。 */
    void writeSecret(ObjectNode credential) {
        Path path = secretFile.toAbsolutePath();
        try {
            Path directory = path.getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }
            Path temp = Files.createTempFile(directory, ".weixin-", ".tmp");
            try {
                if (Files.getFileStore(temp).supportsFileAttributeView("posix")) {
                    Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
                }
            } catch (IOException | UnsupportedOperationException e) {
                // 非 POSIX 文件系统不支持权限视图；文件仍只写入本实例配置的位置。
            }
            json.writeValue(temp.toFile(), credential);
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            secret = null;
        } catch (IOException e) {
            throw new PlatformFailure(ChannelErrorCode.INTERNAL_ERROR, false, "weixin credential file cannot be saved");
        }
    }

    String baseUrl() {
        Secret current = secret();
        String value = current.baseUrl() != null && !current.baseUrl().isBlank()
            ? current.baseUrl() : settingsBaseUrl;
        if (value == null || value.isBlank()) {
            value = DEFAULT_BASE_URL;
        }
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    JsonNode requestQr() {
        ObjectNode body = json.createObjectNode();
        body.set("local_token_list", json.createArrayNode());
        return request("/ilink/bot/get_bot_qrcode", "bot_type=3", body, false, REQUEST_TIMEOUT);
    }

    JsonNode qrStatus(String challenge) {
        return request("/ilink/bot/get_qrcode_status", "qrcode=" + encode(challenge), null, false, REQUEST_TIMEOUT);
    }

    /** 长轮询：游标由调用方保存，只有业务受理后才推进。 */
    JsonNode getUpdates(String cursor) {
        ObjectNode body = json.createObjectNode().put("get_updates_buf", cursor == null ? "" : cursor);
        body.set("base_info", baseInfo());
        return request("/ilink/bot/getupdates", null, body, true, LONG_POLL_TIMEOUT);
    }

    /** 返回平台 message_id 作为受理引用；客户端幂等键固定为 deliveryId。 */
    String sendMessage(String conversation, String contextToken, String clientId, String text, Duration timeout) {
        ObjectNode message = json.createObjectNode()
            .put("to_user_id", conversation)
            .put("client_id", clientId)
            .put("message_type", 2)
            .put("message_state", 2);
        if (contextToken != null && !contextToken.isBlank()) {
            message.put("context_token", contextToken);
        }
        ObjectNode item = json.createObjectNode().put("type", 1);
        item.set("text_item", json.createObjectNode().put("text", text));
        message.set("item_list", json.createArrayNode().add(item));
        ObjectNode body = json.createObjectNode();
        body.set("msg", message);
        body.set("base_info", baseInfo());
        JsonNode result = request("/ilink/bot/sendmessage", null, body, true, timeout);
        int ret = result.path("ret").asInt(0);
        if (ret != 0) {
            ChannelErrorCode code = ret == -14 ? ChannelErrorCode.RATE_LIMITED : ChannelErrorCode.UNAVAILABLE;
            throw new PlatformFailure(code, false, "weixin sendmessage was rejected (ret=" + ret + ")");
        }
        String messageId = result.path("message_id").asText("");
        return messageId.isBlank() ? null : messageId;
    }

    private JsonNode request(String path, String query, JsonNode body, boolean authorized, Duration timeout) {
        URI uri = endpoint(path, query);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout)
            .header("Content-Type", "application/json")
            .header("iLink-App-Id", APP_ID)
            .header("iLink-App-ClientVersion", CLIENT_VERSION)
            .header("X-WECHAT-UIN", uin())
            .header("AuthorizationType", "ilink_bot_token");
        if (authorized) {
            String token = secret().botToken();
            if (token == null || token.isBlank()) {
                throw new PlatformFailure(ChannelErrorCode.AUTH_REQUIRED, false, "weixin bot token is missing");
            }
            builder.header("Authorization", "Bearer " + token);
        }
        try {
            if (body == null) {
                builder.GET();
            } else {
                builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
            }
            return send(builder.build());
        } catch (IOException e) {
            throw new PlatformFailure(ChannelErrorCode.INTERNAL_ERROR, false, "weixin request cannot be built");
        }
    }

    private JsonNode send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new PlatformFailure(ChannelErrorCode.OUTCOME_UNKNOWN, true, "weixin request timed out");
        } catch (IOException e) {
            throw new PlatformFailure(ChannelErrorCode.UNAVAILABLE, true, "weixin request failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformFailure(ChannelErrorCode.OUTCOME_UNKNOWN, true, "weixin request was interrupted");
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new PlatformFailure(ChannelErrorCode.AUTH_REQUIRED, false,
                "weixin rejected the bot token (HTTP " + status + ")");
        }
        if (status / 100 == 4) {
            throw new PlatformFailure(ChannelErrorCode.INVALID_ARGUMENT, false,
                "weixin rejected the request (HTTP " + status + ")");
        }
        if (status != 200) {
            throw new PlatformFailure(ChannelErrorCode.OUTCOME_UNKNOWN, true, "weixin returned HTTP " + status);
        }
        try {
            return json.readTree(response.body());
        } catch (IOException e) {
            throw new PlatformFailure(ChannelErrorCode.OUTCOME_UNKNOWN, true, "weixin response is not JSON");
        }
    }

    private URI endpoint(String path, String query) {
        URI uri = URI.create(baseUrl() + path + (query == null ? "" : "?" + query));
        requireSecure(uri);
        return uri;
    }

    /** 凭据只发往 HTTPS；仅本机开发桩允许明文 HTTP。 */
    static void requireSecure(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if ("https".equals(scheme) || ("http".equals(scheme) && isLoopback(uri.getHost()))) {
            return;
        }
        throw new PlatformFailure(ChannelErrorCode.INVALID_ARGUMENT, false, "weixin endpoint requires HTTPS");
    }

    static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host) || "[::1]".equals(host);
    }

    private ObjectNode baseInfo() {
        return json.createObjectNode().put("channel_version", CHANNEL_VERSION).put("bot_agent", "NetliveCowork");
    }

    private static String uin() {
        int value = ThreadLocalRandom.current().nextInt();
        return Base64.getEncoder().encodeToString(Integer.toUnsignedString(value).getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
