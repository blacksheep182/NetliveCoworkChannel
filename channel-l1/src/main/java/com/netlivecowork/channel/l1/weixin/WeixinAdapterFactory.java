package com.netlivecowork.channel.l1.weixin;

import com.fasterxml.jackson.databind.JsonNode;
import com.netlivecowork.channel.contract.ChannelError;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.EventType;
import com.netlivecowork.channel.contract.adapter.AdapterCapabilities;
import com.netlivecowork.channel.contract.adapter.AdapterConfig;
import com.netlivecowork.channel.contract.adapter.AdapterDescriptor;
import com.netlivecowork.channel.contract.adapter.AdapterFactory;
import com.netlivecowork.channel.contract.adapter.AdapterInstanceConfig;
import com.netlivecowork.channel.contract.adapter.ConnectionTestPort;
import com.netlivecowork.channel.contract.adapter.LifecycleMode;
import com.netlivecowork.channel.contract.adapter.ProbeResult;
import com.netlivecowork.channel.contract.adapter.ValidationResult;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.port.AdapterHost;
import com.netlivecowork.channel.contract.port.SecretAccess;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 微信 iLink 机器人 Adapter 工厂。生命周期 SDK_MANAGED：会话与重连由本 Adapter 自有轮询维护，
 * 只上报连接事实（INV-48）。描述、校验与创建都不联网。
 */
public final class WeixinAdapterFactory implements AdapterFactory, ConnectionTestPort {

    public static final String TYPE = "weixin";
    public static final String IMPLEMENTATION_VERSION = "1.0.0";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final String SETTINGS_SECRET_FILE = "secretFile";
    private static final String SETTINGS_BASE_URL = "baseurl";

    private final HttpClient http;

    public WeixinAdapterFactory() {
        this(HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .build());
    }

    public WeixinAdapterFactory(HttpClient http) {
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public AdapterDescriptor describe() {
        return new AdapterDescriptor(TYPE, "微信 iLink 机器人", IMPLEMENTATION_VERSION, List.of("1"), List.of(1),
            configurationSchema(), Json.obj(), capabilities(), Set.of(LifecycleMode.SDK_MANAGED));
    }

    @Override
    public ValidationResult validateConfig(AdapterConfig config) {
        JsonNode settings = config.settings();
        List<ValidationResult.ValidationIssue> issues = new ArrayList<>();
        if (!settings.isObject()) {
            return ValidationResult.failed("settings", "INVALID", "settings must be a JSON object");
        }
        JsonNode secretFile = settings.get(SETTINGS_SECRET_FILE);
        if (secretFile == null || secretFile.isNull()) {
            issues.add(new ValidationResult.ValidationIssue("settings." + SETTINGS_SECRET_FILE, "REQUIRED",
                "secretFile is required: the bot token is only read from the instance secret file"));
        } else if (!secretFile.isTextual() || secretFile.asText().isBlank()) {
            issues.add(new ValidationResult.ValidationIssue("settings." + SETTINGS_SECRET_FILE, "INVALID",
                "secretFile must be a non-blank path"));
        } else {
            try {
                Path.of(secretFile.asText());
            } catch (InvalidPathException e) {
                issues.add(new ValidationResult.ValidationIssue("settings." + SETTINGS_SECRET_FILE, "INVALID",
                    "secretFile is not a valid path"));
            }
        }
        JsonNode baseUrl = settings.get(SETTINGS_BASE_URL);
        if (baseUrl != null && !baseUrl.isNull()) {
            if (!baseUrl.isTextual() || baseUrl.asText().isBlank()) {
                issues.add(new ValidationResult.ValidationIssue("settings." + SETTINGS_BASE_URL, "INVALID",
                    "baseurl must be a non-blank URL"));
            } else {
                try {
                    WeixinProtocol.requireSecure(URI.create(baseUrl.asText()));
                } catch (IllegalArgumentException | WeixinProtocol.PlatformFailure e) {
                    issues.add(new ValidationResult.ValidationIssue("settings." + SETTINGS_BASE_URL, "INSECURE_ENDPOINT",
                        "baseurl must be an absolute https URL (http is allowed only for loopback stubs)"));
                }
            }
        }
        // 明文凭据只允许在实例凭据文件里，不进入 settings（INV-52）
        for (String field : List.of("botToken", "bot_token")) {
            if (settings.hasNonNull(field)) {
                issues.add(new ValidationResult.ValidationIssue("settings." + field, "SECRET_IN_SETTINGS",
                    "credentials must not be placed in settings; use the instance secret file"));
            }
        }
        return issues.isEmpty() ? ValidationResult.ok() : ValidationResult.failed(issues);
    }

    @Override
    public WeixinAdapterInstance create(AdapterInstanceConfig config, AdapterHost host) {
        return new WeixinAdapterInstance(config, host, http);
    }

    /** 管理端连接测试：用本实例凭据做一次只读 getupdates，不提交业务、不推进游标。 */
    @Override
    public ConnectionTestPort connectionTest() {
        return this;
    }

    @Override
    public CompletionStage<ProbeResult> testConnection(AdapterConfig config, SecretAccess secrets, long timeoutMs) {
        CompletableFuture<ProbeResult> result = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                ValidationResult validation = validateConfig(config);
                if (!validation.valid()) {
                    ValidationResult.ValidationIssue issue = validation.issues().get(0);
                    result.complete(ProbeResult.unhealthy(
                        ChannelError.of(ChannelErrorCode.INVALID_ARGUMENT, issue.message(), issue.path())));
                    return;
                }
                WeixinProtocol protocol = new WeixinProtocol(http, secretFile(config.settings()),
                    baseUrl(config.settings()));
                if (!protocol.refreshSecret().hasToken()) {
                    result.complete(ProbeResult.unhealthy(
                        ChannelErrorCode.AUTH_REQUIRED.error("weixin credential is missing")));
                    return;
                }
                protocol.getUpdates("");
                result.complete(ProbeResult.healthy());
            } catch (WeixinProtocol.PlatformFailure e) {
                result.complete(e.outcomeUnknown()
                    ? ProbeResult.unknown(e.getMessage())
                    : ProbeResult.unhealthy(ChannelError.of(e.code(), e.getMessage())));
            } catch (RuntimeException e) {
                result.complete(ProbeResult.unknown("weixin connection test could not be completed"));
            }
        });
        return result;
    }

    static Path secretFile(JsonNode settings) {
        return Path.of(settings.path(SETTINGS_SECRET_FILE).asText());
    }

    static String baseUrl(JsonNode settings) {
        return settings.path(SETTINGS_BASE_URL).asText("");
    }

    static AdapterCapabilities capabilities() {
        return new AdapterCapabilities(LifecycleMode.SDK_MANAGED, true, true, true, true,
            AdapterCapabilities.Files.none(),
            new AdapterCapabilities.Delivery(Set.of(EventType.values()), Set.of(ContentPart.PartType.TEXT), true,
                AdapterCapabilities.Idempotency.LOCAL_ONLY, false, null, null, AdapterCapabilities.Handover.PROVIDER, AdapterCapabilities.CompletionNotice.TEXT));
    }

    private static JsonNode configurationSchema() {
        var schema = Json.obj();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        var properties = schema.putObject("properties");
        properties.putObject(SETTINGS_SECRET_FILE).put("type", "string")
            .put("title", "凭据文件路径")
            .put("description", "只保存 bot_token/ilink_bot_id/baseurl 的实例凭据文件");
        properties.putObject(SETTINGS_BASE_URL).put("type", "string").put("format", "uri")
            .put("title", "iLink 端点").put("description", "默认使用 iLink 公共端点；仅本机桩允许 http");
        var required = schema.putArray("required");
        required.add(SETTINGS_SECRET_FILE);
        schema.put("additionalProperties", false);
        return schema;
    }
}
