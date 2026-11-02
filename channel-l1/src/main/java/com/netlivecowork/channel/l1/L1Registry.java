package com.netlivecowork.channel.l1;

import com.netlivecowork.channel.contract.adapter.AdapterDescriptor;
import com.netlivecowork.channel.contract.adapter.AdapterFactory;
import com.netlivecowork.channel.contract.port.AdapterCatalog;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L1 Adapter 目录：装配成功的类型与有效描述。未知类型不静默创建实例（INV-03）；
 * 同类型多实例由工厂 + 实例配置表达，这里只按 type 索引工厂。
 */
public final class L1Registry implements AdapterCatalog {

    private final Map<String, AdapterFactory> factories;
    private final Map<String, AdapterDescriptor> descriptors;

    public L1Registry(List<AdapterFactory> factories) {
        if (factories == null) {
            throw new IllegalStateException("adapter factories are required");
        }
        Map<String, AdapterFactory> registered = new LinkedHashMap<>();
        Map<String, AdapterDescriptor> described = new LinkedHashMap<>();
        for (AdapterFactory factory : factories) {
            if (factory == null) {
                throw new IllegalStateException("adapter factory must not be null (registered: " + registered.keySet() + ")");
            }
            AdapterDescriptor descriptor = factory.describe();
            if (descriptor == null) {
                throw new IllegalStateException("adapter factory returned no descriptor (registered: " + registered.keySet() + ")");
            }
            String type = descriptor.type();
            if (type == null || type.isBlank()) {
                throw new IllegalStateException("adapter type must not be blank (registered: " + registered.keySet() + ")");
            }
            if (registered.putIfAbsent(type, factory) != null) {
                throw new IllegalStateException("duplicate adapter type: " + type + " (registered: " + registered.keySet() + ")");
            }
            described.put(type, descriptor);
        }
        this.factories = Collections.unmodifiableMap(registered);
        this.descriptors = Collections.unmodifiableMap(described);
    }

    @Override
    public List<AdapterDescriptor> descriptors() {
        return List.copyOf(descriptors.values());
    }

    @Override
    public AdapterFactory factory(String type) {
        return type == null ? null : factories.get(type);
    }

    @Override
    public AdapterDescriptor descriptor(String type) {
        return type == null ? null : descriptors.get(type);
    }
}
