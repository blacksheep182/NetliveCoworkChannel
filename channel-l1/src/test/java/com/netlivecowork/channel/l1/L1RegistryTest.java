package com.netlivecowork.channel.l1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netlivecowork.channel.contract.adapter.AdapterCapabilities;
import com.netlivecowork.channel.contract.adapter.AdapterConfig;
import com.netlivecowork.channel.contract.adapter.AdapterDescriptor;
import com.netlivecowork.channel.contract.adapter.AdapterFactory;
import com.netlivecowork.channel.contract.adapter.AdapterInstance;
import com.netlivecowork.channel.contract.adapter.AdapterInstanceConfig;
import com.netlivecowork.channel.contract.adapter.LifecycleMode;
import com.netlivecowork.channel.contract.adapter.ValidationResult;
import com.netlivecowork.channel.contract.json.Json;
import com.netlivecowork.channel.contract.port.AdapterHost;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class L1RegistryTest {

    private static AdapterCapabilities capabilities() {
        return new AdapterCapabilities(LifecycleMode.PASSIVE, false, false, false, false,
            AdapterCapabilities.Files.none(), null);
    }

    private static AdapterFactory factory(String type) {
        return new AdapterFactory() {
            @Override
            public AdapterDescriptor describe() {
                return new AdapterDescriptor(type, type, "1.0.0", List.of("1"), List.of(1),
                    Json.obj(), Json.obj(), capabilities(), Set.of(LifecycleMode.PASSIVE));
            }

            @Override
            public ValidationResult validateConfig(AdapterConfig config) {
                return ValidationResult.ok();
            }

            @Override
            public AdapterInstance create(AdapterInstanceConfig config, AdapterHost host) {
                throw new UnsupportedOperationException("test factory does not create instances");
            }
        };
    }

    private static AdapterFactory undescribed() {
        return new AdapterFactory() {
            @Override
            public AdapterDescriptor describe() {
                return null;
            }

            @Override
            public ValidationResult validateConfig(AdapterConfig config) {
                return ValidationResult.ok();
            }

            @Override
            public AdapterInstance create(AdapterInstanceConfig config, AdapterHost host) {
                throw new UnsupportedOperationException("test factory does not create instances");
            }
        };
    }

    @Test
    void rejectsDuplicateAdapterType() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new L1Registry(List.of(factory("dup"), factory("dup"))));
        assertTrue(error.getMessage().contains("dup"), error.getMessage());
    }

    @Test
    void rejectsNullFactory() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new L1Registry(Arrays.asList(factory("only"), null)));
        assertTrue(error.getMessage().contains("only"), error.getMessage());
    }

    @Test
    void rejectsNullFactoryList() {
        assertThrows(IllegalStateException.class, () -> new L1Registry(null));
    }

    @Test
    void rejectsFactoryWithoutDescriptor() {
        assertThrows(IllegalStateException.class, () -> new L1Registry(List.of(undescribed())));
    }

    /** 契约本身已拒绝空类型，注册表仍保留同样判断作为防御。 */
    @Test
    void blankTypeIsRejectedByContract() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new AdapterDescriptor("  ", "blank", "1.0.0", List.of("1"), List.of(1), Json.obj(), Json.obj(),
                capabilities(), Set.of(LifecycleMode.PASSIVE)));
        assertTrue(error.getMessage().contains("type"), error.getMessage());
    }

    @Test
    void exposesDescriptorAndFactoryByType() {
        AdapterFactory first = factory("alpha");
        AdapterFactory second = factory("beta");
        L1Registry registry = new L1Registry(List.of(first, second));

        assertEquals(List.of("alpha", "beta"),
            registry.descriptors().stream().map(AdapterDescriptor::type).toList());
        assertSame(first, registry.factory("alpha"));
        assertSame(second, registry.factory("beta"));
        assertNotNull(registry.descriptor("beta"));
        assertEquals("beta", registry.descriptor("beta").type());
        assertNull(registry.factory("missing"));
        assertNull(registry.factory(null));
        assertNull(registry.descriptor("missing"));
    }
}
