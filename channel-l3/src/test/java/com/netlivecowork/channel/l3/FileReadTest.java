package com.netlivecowork.channel.l3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.FileMetadata;
import com.netlivecowork.channel.contract.FileReadResult;
import com.netlivecowork.channel.infra.Db;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 出站文件读取：能力门槛、不透明关联定位与大小上限（INV-41～46）。 */
class FileReadTest {

    @TempDir
    Path tempDir;

    @Test
    void readFileWithoutCapabilityFails() throws Exception {
        Db db = TestFixtures.db(tempDir);
        try (TestBackend backend = new TestBackend(call -> TestBackend.Response.status(200));
             L3Module module = new L3Module(db, Clock.systemUTC(), TestFixtures.settings(),
                 new TestFixtures.RecordingIntake())) {
            TestFixtures.seed(module, backend.baseUrl());

            ExecutionException failure = assertThrows(ExecutionException.class,
                () -> module.access().readFile("file-1", "delivery-1").toCompletableFuture().get());

            ChannelContractException cause = assertInstanceOf(ChannelContractException.class, failure.getCause());
            assertEquals(ChannelErrorCode.UNSUPPORTED_CAPABILITY, cause.code());
            assertEquals(0, backend.callCount());
        }
    }

    @Test
    void readFileStreamsRegisteredBackendBytes() throws Exception {
        Db db = TestFixtures.db(tempDir);
        byte[] payload = "backend-file-bytes".getBytes(StandardCharsets.UTF_8);
        try (TestBackend backend = new TestBackend(
                call -> new TestBackend.Response(200, new String(payload, StandardCharsets.UTF_8), 0));
             L3Module module = new L3Module(db, Clock.systemUTC(),
                 TestFixtures.settings(2_000L, 2, true, false), new TestFixtures.RecordingIntake())) {
            TestFixtures.seed(module, backend.baseUrl());
            module.registerFile("file-1", TestFixtures.TARGET, "backend-file-1",
                new FileMetadata("report.txt", "text/plain", payload.length));

            FileReadResult result = module.access().readFile("file-1", "delivery-1")
                .toCompletableFuture().get();

            assertEquals("report.txt", result.metadata().name());
            assertArrayEquals(payload, result.data());
            assertEquals("GET", backend.lastCall().method());
            assertEquals("/api/v1/workspace/files/backend-file-1", backend.lastCall().path());
        }
    }

    @Test
    void unknownFileRefFails() throws Exception {
        Db db = TestFixtures.db(tempDir);
        try (TestBackend backend = new TestBackend(call -> TestBackend.Response.status(200));
             L3Module module = new L3Module(db, Clock.systemUTC(),
                 TestFixtures.settings(2_000L, 2, true, false), new TestFixtures.RecordingIntake())) {
            TestFixtures.seed(module, backend.baseUrl());

            ExecutionException failure = assertThrows(ExecutionException.class,
                () -> module.access().readFile("file-unknown", "delivery-1").toCompletableFuture().get());

            ChannelContractException cause = assertInstanceOf(ChannelContractException.class, failure.getCause());
            assertEquals(ChannelErrorCode.REQUEST_NOT_FOUND, cause.code());
        }
    }
}
