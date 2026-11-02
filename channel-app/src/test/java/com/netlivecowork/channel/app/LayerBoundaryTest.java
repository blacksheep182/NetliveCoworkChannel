package com.netlivecowork.channel.app;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 层边界回归（INV-47）：Maven enforcer 守住模块依赖，本测试守住源码级 import 方向，
 * 防止有人绕过模块依赖图在代码里直连相邻层之外的类型。
 */
class LayerBoundaryTest {

    /** 每层禁止直接 import 的模块前缀。 */
    private static final Map<String, List<String>> FORBIDDEN = Map.of(
        "channel-contract", List.of("infra", "l1", "l2", "l3", "app"),
        "channel-infra", List.of("contract", "l1", "l2", "l3", "app"),
        "channel-l1", List.of("l2", "l3", "app"),
        "channel-l2", List.of("l1", "l3", "app"),
        "channel-l3", List.of("l1", "l2", "app"));

    @Test
    void mainSourcesOnlyImportAdjacentLayers() throws IOException {
        Path root = repositoryRoot();
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, List<String>> rule : FORBIDDEN.entrySet()) {
            Path sources = root.resolve(rule.getKey()).resolve("src/main/java");
            assertTrue(Files.isDirectory(sources), "missing sources: " + sources);
            for (String forbidden : rule.getValue()) {
                String prefix = "import com.netlivecowork.channel." + forbidden + ".";
                try (Stream<Path> files = Files.walk(sources)) {
                    for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                        List<String> lines = Files.readAllLines(file);
                        for (int i = 0; i < lines.size(); i++) {
                            if (lines.get(i).strip().startsWith(prefix)) {
                                violations.add(rule.getKey() + " -> " + forbidden + ": "
                                    + root.relativize(file) + ":" + (i + 1));
                            }
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "layer boundary violated: " + violations);
    }

    private Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("channel-contract").resolve("src/main/java"))
                && Files.isDirectory(current.resolve("channel-l2").resolve("src/main/java"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate the multi-module repository root");
    }
}
