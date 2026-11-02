package com.netlivecowork.channel.contract;

import java.util.Objects;

/** 规范包创建者；转发不改写（INV-17、INV-28）。 */
public record Producer(Layer layer, String componentRef) {
    public Producer {
        Objects.requireNonNull(layer, "layer");
        requireText(componentRef, "componentRef");
    }

    static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
