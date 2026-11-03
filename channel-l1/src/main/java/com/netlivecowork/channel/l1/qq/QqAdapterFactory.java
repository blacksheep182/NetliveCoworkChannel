package com.netlivecowork.channel.l1.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.EventType;
import com.netlivecowork.channel.contract.adapter.*;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.port.AdapterHost;
import com.netlivecowork.channel.contract.port.SecretAccess;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** QQ official bot, group @ messages and private binding messages. */
public final class QqAdapterFactory implements AdapterFactory, ConnectionTestPort {
    public static final String TYPE = "qq";
    private final HttpClient http;
    public QqAdapterFactory() { this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()); }
    public QqAdapterFactory(HttpClient http) { this.http = http; }

    static AdapterCapabilities capabilities() {
        return new AdapterCapabilities(LifecycleMode.MANAGED, true, true, false, true,
            AdapterCapabilities.Files.none(),
            new AdapterCapabilities.Delivery(Set.of(EventType.values()), Set.of(ContentPart.PartType.TEXT),
                true, AdapterCapabilities.Idempotency.LOCAL_ONLY, false, null, null,
                AdapterCapabilities.Handover.PROVIDER, AdapterCapabilities.CompletionNotice.TEXT));
    }

    @Override public AdapterDescriptor describe() {
        var schema = Json.obj().put("$schema", "https://json-schema.org/draft/2020-12/schema")
            .put("type", "object");
        var fields = schema.putObject("properties");
        fields.putObject("appId").put("type", "string").put("title", "QQ 机器人 AppID");
        fields.putObject("secretFile").put("type", "string").put("title", "凭据文件路径")
            .put("description", "JSON 文件只包含 clientSecret；不要把密钥写入入口设置");
        fields.putObject("apiBaseUrl").put("type", "string").put("format", "uri")
            .put("title", "QQ OpenAPI 地址");
        schema.putArray("required").add("appId").add("secretFile");
        schema.put("additionalProperties", false);
        return new AdapterDescriptor(TYPE, "QQ 官方机器人", "1.0.0", List.of("1"), List.of(1),
            schema, Json.obj(), capabilities(), Set.of(LifecycleMode.MANAGED));
    }

    @Override public ValidationResult validateConfig(AdapterConfig config) {
        JsonNode s = config.settings();
        if (!s.isObject()) return ValidationResult.failed("settings", "INVALID", "settings must be an object");
        var issues = new ArrayList<ValidationResult.ValidationIssue>();
        for (String field : List.of("appId", "secretFile")) {
            if (!s.path(field).isTextual() || s.path(field).asText().isBlank())
                issues.add(new ValidationResult.ValidationIssue("settings." + field, "REQUIRED", field + " is required"));
        }
        for (String field : List.of("clientSecret", "appSecret", "token")) {
            if (s.has(field)) issues.add(new ValidationResult.ValidationIssue("settings." + field,
                "SECRET_IN_SETTINGS", "put the QQ client secret in secretFile"));
        }
        for (String field : List.of("apiBaseUrl")) {
            if (s.has(field)) try { QqProtocol.requireOfficialApi(URI.create(s.path(field).asText())); }
                catch (RuntimeException e) { issues.add(new ValidationResult.ValidationIssue("settings." + field,
                    "INSECURE_ENDPOINT", "endpoint must use HTTPS (HTTP only for loopback fixtures)")); }
        }
        if (s.has("tokenUrl")) issues.add(new ValidationResult.ValidationIssue("settings.tokenUrl",
            "INVALID", "QQ token endpoint is fixed by the adapter"));
        if (s.path("secretFile").isTextual()) try { Path.of(s.path("secretFile").asText()); }
            catch (RuntimeException e) { issues.add(new ValidationResult.ValidationIssue("settings.secretFile",
                "INVALID", "secretFile path is invalid")); }
        return issues.isEmpty() ? ValidationResult.ok() : ValidationResult.failed(issues);
    }

    @Override public QqAdapterInstance create(AdapterInstanceConfig config, AdapterHost host) {
        return new QqAdapterInstance(config, host, protocol(config.settings()));
    }
    private QqProtocol protocol(JsonNode s) {
        return new QqProtocol(http, s.path("appId").asText(), Path.of(s.path("secretFile").asText()),
            QqProtocol.requireOfficialApi(URI.create(s.path("apiBaseUrl").asText(QqProtocol.DEFAULT_API.toString()))),
            QqProtocol.DEFAULT_TOKEN);
    }
    @Override public ConnectionTestPort connectionTest() { return this; }
    @Override public CompletionStage<ProbeResult> testConnection(AdapterConfig config, SecretAccess secrets, long timeoutMs) {
        return CompletableFuture.supplyAsync(() -> {
            var validation = validateConfig(config);
            if (!validation.valid()) return ProbeResult.unhealthy(
                com.netlivecowork.channel.contract.ChannelErrorCode.INVALID_ARGUMENT.error(validation.issues().get(0).message()));
            try { protocol(config.settings()).gateway(); return ProbeResult.healthy(); }
            catch (QqProtocol.PlatformFailure e) {
                return e.failure.unknown() ? ProbeResult.unknown(e.getMessage()) :
                    ProbeResult.unhealthy(e.failure.code().error(e.getMessage()));
            }
        });
    }
}
