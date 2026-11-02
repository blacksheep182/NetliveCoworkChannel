package com.netlivecowork.channel.app;

import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.l3.ServiceCredentialResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolve only deployment-controlled references; never return secret values in diagnostics. */
final class DeploymentCredentials implements ServiceCredentialResolver {
    @Override
    public String resolve(String reference) {
        if (reference == null || reference.isBlank()) return null;
        try {
            String value;
            if (reference.startsWith("env:")) {
                String name = reference.substring(4);
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException();
                value = System.getenv(name);
            } else if (reference.startsWith("file:")) {
                Path path = Path.of(reference.substring(5));
                if (!path.isAbsolute()) throw new IllegalArgumentException();
                try (var input = Files.newInputStream(path)) {
                    byte[] bytes = input.readNBytes(16_385);
                    if (bytes.length > 16_384) throw new IllegalArgumentException();
                    value = new String(bytes, StandardCharsets.UTF_8).strip();
                }
            } else {
                throw new IllegalArgumentException();
            }
            if (value == null || value.isBlank() || value.length() > 16_384
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw new IllegalArgumentException();
            return value;
        } catch (Exception error) {
            throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE,
                "configured backend credential is unavailable");
        }
    }
}
