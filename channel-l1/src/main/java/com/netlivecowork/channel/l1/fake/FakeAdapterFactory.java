package com.netlivecowork.channel.l1.fake;

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
import java.util.List;
import java.util.Set;

/** 测试用 Adapter 工厂：记录投递并通过宿主端口提交入站，不访问任何平台。 */
public final class FakeAdapterFactory implements AdapterFactory {

    public static final String TYPE = "fake";
    public static final String IMPLEMENTATION_VERSION = "1.0.0";

    @Override
    public AdapterDescriptor describe() {
        return new AdapterDescriptor(TYPE, "测试入口", IMPLEMENTATION_VERSION, List.of("1"), List.of(1),
            Json.obj(), Json.obj(), capabilities(), Set.of(LifecycleMode.PASSIVE));
    }

    @Override
    public ValidationResult validateConfig(AdapterConfig config) {
        return ValidationResult.ok();
    }

    @Override
    public FakeAdapterInstance create(AdapterInstanceConfig config, AdapterHost host) {
        return new FakeAdapterInstance(config, host);
    }

    static AdapterCapabilities capabilities() {
        return new AdapterCapabilities(LifecycleMode.PASSIVE, true, true, false, false,
            AdapterCapabilities.Files.none(),
            new AdapterCapabilities.Delivery(Set.of(EventType.values()), Set.of(ContentPart.PartType.TEXT), false,
                AdapterCapabilities.Idempotency.LOCAL_ONLY, false, null, null, AdapterCapabilities.Handover.PROVIDER, AdapterCapabilities.CompletionNotice.STRUCTURED));
    }
}
