package com.netlivecowork.channel.l1.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.netlivecowork.channel.contract.ContentPart;
import com.netlivecowork.channel.contract.EventType;
import com.netlivecowork.channel.contract.adapter.AdapterCapabilities;
import com.netlivecowork.channel.contract.adapter.AdapterConfig;
import com.netlivecowork.channel.contract.adapter.AdapterDescriptor;
import com.netlivecowork.channel.contract.adapter.AdapterFactory;
import com.netlivecowork.channel.contract.adapter.AdapterInstanceConfig;
import com.netlivecowork.channel.contract.adapter.LifecycleMode;
import com.netlivecowork.channel.contract.adapter.ValidationResult;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.port.AdapterHost;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 通用 Web / 小程序入口 Adapter 工厂。生命周期 PASSIVE：没有长连接与心跳，激活后即可接收请求；
 * 投递结果由本实例缓冲供客户端轮询，平台不提供回执查询。
 */
public final class WebAdapterFactory implements AdapterFactory {

    public static final String TYPE = "web";
    public static final String IMPLEMENTATION_VERSION = "1.0.0";

    static final String SETTINGS_USERS = "users";
    static final String SETTINGS_USERS_FILE = "usersFile";
    static final String RELAY_DIGEST = "hostRelayDigest";
    static final String RELAY_SUBJECT = "host-web";

    @Override
    public AdapterDescriptor describe() {
        return new AdapterDescriptor(TYPE, "Web / 小程序入口", IMPLEMENTATION_VERSION, List.of("1"), List.of(1),
            configurationSchema(), Json.obj(), capabilities(), Set.of(LifecycleMode.PASSIVE));
    }

    @Override
    public ValidationResult validateConfig(AdapterConfig config) {
        JsonNode settings = config.settings();
        if (!settings.isObject()) {
            return ValidationResult.failed("settings", "INVALID", "settings must be a JSON object");
        }
        List<ValidationResult.ValidationIssue> issues = new ArrayList<>();
        if(settings.has("inbox")) {
            JsonNode inbox=settings.path("inbox");
            if(!inbox.isObject())issues.add(new ValidationResult.ValidationIssue("settings.inbox","INVALID","inbox must be an object"));
            else for(String name:List.of("maxItems","retentionSeconds","cursorTtlSeconds")) {
                long min=name.equals("maxItems")?1:60,max=name.equals("maxItems")?1000000:name.equals("retentionSeconds")?31536000:86400;
                if(inbox.has(name)&&(!inbox.path(name).isIntegralNumber()||!inbox.path(name).canConvertToLong()||inbox.path(name).asLong()<min||inbox.path(name).asLong()>max))
                    issues.add(new ValidationResult.ValidationIssue("settings.inbox."+name,"INVALID","inbox policy is outside supported limits"));
            }
        }
        if(settings.has("historyAccess") && (!settings.path("historyAccess").isTextual()
            || !Set.of("none","summary","full").contains(settings.path("historyAccess").asText())))
            issues.add(new ValidationResult.ValidationIssue("settings.historyAccess","INVALID","historyAccess must be none, summary or full"));
        JsonNode users = settings.get(SETTINGS_USERS);
        String usersFile = settings.path(SETTINGS_USERS_FILE).asText("");
        if (settings.has(RELAY_DIGEST)) {
            if (users != null || !usersFile.isBlank() || !settings.path(RELAY_DIGEST).asText().matches("[a-f0-9]{64}"))
                issues.add(new ValidationResult.ValidationIssue("settings.hostRelayDigest", "INVALID", "Invalid host relay identity"));
            return issues.isEmpty() ? ValidationResult.ok() : ValidationResult.failed(issues);
        }
        if (users != null && !users.isNull()) {
            if (!users.isObject() || users.isEmpty()) {
                issues.add(new ValidationResult.ValidationIssue("settings." + SETTINGS_USERS, "INVALID",
                    "users must map caller names to tokens, e.g. {\"alice\":\"token\"}"));
            } else {
                users.fields().forEachRemaining(entry -> {
                    if (entry.getKey().isBlank()) {
                        issues.add(new ValidationResult.ValidationIssue("settings." + SETTINGS_USERS, "INVALID",
                            "user name must not be blank"));
                    }
                    if (!entry.getValue().isTextual() || entry.getValue().asText().isBlank()) {
                        issues.add(new ValidationResult.ValidationIssue("settings." + SETTINGS_USERS + "." + entry.getKey(),
                            "INVALID", "user token must be a non-blank string"));
                    }
                });
            }
        } else if (usersFile.isBlank()) {
            issues.add(new ValidationResult.ValidationIssue("settings." + SETTINGS_USERS, "REQUIRED",
                "users object or usersFile path is required"));
        } else {
            try {
                Path.of(usersFile);
            } catch (InvalidPathException e) {
                issues.add(new ValidationResult.ValidationIssue("settings." + SETTINGS_USERS_FILE, "INVALID",
                    "usersFile is not a valid path"));
            }
        }
        return issues.isEmpty() ? ValidationResult.ok() : ValidationResult.failed(issues);
    }

    @Override
    public WebAdapterInstance create(AdapterInstanceConfig config, AdapterHost host) {
        return new WebAdapterInstance(config, host);
    }

    static AdapterCapabilities capabilities() {
        // 纯文本入口：动作、图片与附件一律不支持（INV-38）
        return new AdapterCapabilities(LifecycleMode.PASSIVE, true, true, false, false,
            AdapterCapabilities.Files.none(),
            new AdapterCapabilities.Delivery(Set.of(EventType.values()), Set.of(ContentPart.PartType.TEXT), true,
                AdapterCapabilities.Idempotency.LOCAL_ONLY, true, null, null, AdapterCapabilities.Handover.DURABLE_INBOX, AdapterCapabilities.CompletionNotice.STRUCTURED), AdapterCapabilities.HistoryAccess.FULL);
    }

    private static JsonNode configurationSchema() {
        var schema = Json.obj();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        var properties = schema.putObject("properties");
        properties.putObject(SETTINGS_USERS).put("type", "object")
            .put("title", "访问令牌").put("description", "{\"alice\":\"token\"}：令牌只映射到调用者引用");
        properties.putObject(SETTINGS_USERS_FILE).put("type", "string")
            .put("title", "令牌文件路径").put("description", "首次使用时读取，内容与 users 相同");
        var history=properties.putObject("historyAccess").put("type","string").put("default","full")
            .put("title","会话查询能力");
        history.putArray("enum").add("none").add("summary").add("full");
        schema.put("additionalProperties", false);
        return schema;
    }
}
