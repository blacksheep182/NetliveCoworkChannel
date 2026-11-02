package com.netlivecowork.channel.l3;

/** Encryption supplied by the host's existing credential facility. No L2 implementation dependency. */
public interface DeviceSecrets {
    String seal(String value);
    String open(String value);
    DeviceSecrets DISABLED = new DeviceSecrets() {
        public String seal(String v) { throw new IllegalStateException("Device credential protection is not configured"); }
        public String open(String v) { throw new IllegalStateException("Device credential protection is not configured"); }
    };
}
