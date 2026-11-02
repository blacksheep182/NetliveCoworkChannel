package com.netlivecowork.channel.l3;

import com.fasterxml.jackson.databind.JsonNode;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.infra.Digests;
import java.util.ArrayList;
import java.util.Collections;

/** RFC8785 subset used by this protocol: Unicode strings and safe integral numbers only. */
public final class ProtocolJson {
    private ProtocolJson() {}
    public static String canonical(JsonNode value) {
        if (value.isObject()) {
            var keys = new ArrayList<String>();
            value.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys); // Java String compares UTF-16 code units.
            var parts = new ArrayList<String>();
            for (String key : keys) parts.add(quote(key) + ":" + canonical(value.get(key)));
            return "{" + String.join(",", parts) + "}";
        }
        if (value.isArray()) {
            var parts = new ArrayList<String>();
            value.forEach(item -> parts.add(canonical(item)));
            return "[" + String.join(",", parts) + "]";
        }
        if (value.isTextual()) return quote(value.textValue());
        if (value.isNull() || value.isBoolean()) return value.toString();
        if (value.isIntegralNumber() && value.canConvertToLong()
            && value.longValue() >= -9007199254740991L && value.longValue() <= 9007199254740991L)
            return value.toString();
        throw Json.invalid("unsupported protocol JSON value");
    }
    public static String digest(JsonNode value) { return Digests.sha256(canonical(value)); }
    private static String quote(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw Json.invalid("invalid Unicode");
            } else if (Character.isLowSurrogate(c)) throw Json.invalid("invalid Unicode");
        }
        return Json.write(com.fasterxml.jackson.databind.node.TextNode.valueOf(text));
    }
}
