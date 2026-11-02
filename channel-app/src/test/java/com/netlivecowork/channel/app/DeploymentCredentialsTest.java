package com.netlivecowork.channel.app;

import static org.junit.jupiter.api.Assertions.*;
import com.netlivecowork.channel.contract.ChannelContractException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentCredentialsTest {
    @TempDir Path directory;
    @Test void absoluteFileProofResolvesAndRelativeFileIsRejected() throws Exception {
        Path proof = directory.resolve("w3-session-proof.txt");
        Files.writeString(proof,"test-proof\n");
        DeploymentCredentials credentials = new DeploymentCredentials();
        assertEquals("test-proof",credentials.resolve("file:" + proof.toAbsolutePath()));
        assertThrows(ChannelContractException.class,() -> credentials.resolve("file:./data/w3-session-proof.txt"));
    }
}
