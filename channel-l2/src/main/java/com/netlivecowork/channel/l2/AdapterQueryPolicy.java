package com.netlivecowork.channel.l2;

import com.netlivecowork.channel.contract.adapter.AdapterCapabilities.HistoryAccess;
import com.netlivecowork.channel.contract.adapter.EntryConfig;
import com.netlivecowork.channel.contract.port.AdapterCatalog;

/** Effective read capability is the intersection of trusted descriptor and instance restriction. */
final class AdapterQueryPolicy {
    static HistoryAccess access(AdapterCatalog catalog, EntryConfig entry) {
        var descriptor = catalog.descriptor(entry.adapterType());
        if (descriptor == null) return HistoryAccess.NONE;
        var ceiling = descriptor.capabilities().historyAccess();
        String configured = entry.settings().path("historyAccess").asText("");
        if (configured.isEmpty()) return ceiling;
        try {
            var restriction = HistoryAccess.valueOf(configured.toUpperCase(java.util.Locale.ROOT));
            return ceiling.ordinal() < restriction.ordinal() ? ceiling : restriction;
        } catch (IllegalArgumentException e) { return HistoryAccess.NONE; }
    }
}
