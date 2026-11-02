package com.netlivecowork.channel.contract.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * 契约 JSON 工具：严格读取，未知版本/类型/枚举与不符合 schema 的内容一律拒绝
 * （data-contracts.md 约定）。未定义字段同样拒绝，避免字段由解析器擅自补全。
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectNode obj() {
        return MAPPER.createObjectNode();
    }

    public static ObjectNode obj(String field, String value) {
        return MAPPER.createObjectNode().put(field, value);
    }

    public static ArrayNode arr() {
        return MAPPER.createArrayNode();
    }

    public static JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "JSON body is empty");
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, "invalid JSON body");
        }
    }

    public static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new ChannelContractException(ChannelErrorCode.INTERNAL_ERROR, "cannot serialize contract value");
        }
    }

    public static JsonNode obj(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) {
            throw invalid(field + " object is required");
        }
        return value;
    }

    public static JsonNode optObj(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : obj(node, field);
    }

    public static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid(field + " string is required");
        }
        return value.asText();
    }

    public static String optText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(field + " must be a string");
        }
        return value.asText().isBlank() ? null : value.asText();
    }

    public static long reqLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw invalid(field + " integer is required");
        }
        return value.asLong();
    }

    public static long optLong(JsonNode node, String field, long fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isIntegralNumber()) {
            throw invalid(field + " must be an integer");
        }
        return value.asLong();
    }

    public static boolean optBool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isBoolean()) {
            throw invalid(field + " must be a boolean");
        }
        return value.asBoolean();
    }

    public static Double optDouble(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw invalid(field + " must be a number");
        }
        return value.asDouble();
    }

    public static Instant instant(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid(field + " timestamp is required");
        }
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException e) {
            throw invalid(field + " timestamp must be ISO-8601");
        }
    }

    public static Instant optInstant(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : instant(node, field);
    }

    public static ArrayNode array(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw invalid(field + " array is required");
        }
        return (ArrayNode) value;
    }

    public static ArrayNode optArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return arr();
        }
        if (!value.isArray()) {
            throw invalid(field + " must be an array");
        }
        return (ArrayNode) value;
    }

    /** 枚举线值必须是已定义名称，不做大小写或别名容错。 */
    public static <E extends Enum<E>> E enumValue(JsonNode node, String field, Class<E> type) {
        String raw = text(node, field);
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw invalid(field + " has unknown value: " + raw);
        }
    }

    /** 未定义字段一律拒绝：契约写明“未定义字段及不符合 schema 的内容一律拒绝”。 */
    public static void onlyFields(JsonNode node, String... allowed) {
        if (!node.isObject()) {
            throw invalid("object is required");
        }
        Set<String> allowedSet = new HashSet<>(Set.of(allowed));
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowedSet.contains(name)) {
                throw invalid("undefined field: " + name);
            }
        }
    }

    public static ChannelContractException invalid(String message) {
        return new ChannelContractException(ChannelErrorCode.INVALID_ARGUMENT, message);
    }
}
