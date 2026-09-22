package com.lian.aicode.ai.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证模型文件工具的路径、敏感文件和工程容量边界。 */
class ProjectPathPolicyTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsTraversalAndSensitiveFiles() {
        ProjectPathPolicy policy = newPolicy(10, 10_000, 2_000);

        assertThrows(RuntimeException.class, () -> policy.resolveFile("../outside.txt"));
        assertThrows(RuntimeException.class, () -> policy.resolveFile(".env"));
        assertThrows(RuntimeException.class, () -> policy.resolveFile(".npmrc"));
        assertThrows(RuntimeException.class, () -> policy.resolveFile("src/../.env.local"));
    }

    @Test
    void protectsTemplateBuildFilesButAllowsSourceFiles() {
        ProjectPathPolicy policy = newPolicy(10, 10_000, 2_000);
        Path packageJson = tempDir.resolve("package.json");
        Path sourceFile = tempDir.resolve("src/NewPage.vue");

        assertThrows(RuntimeException.class,
                () -> policy.assertWritable(packageJson, "{}"));
        assertDoesNotThrow(() -> policy.assertWritable(sourceFile, "<template>ok</template>"));
    }

    @Test
    void enforcesFileCountAndUtf8ByteLimits() throws Exception {
        ProjectToolContext context = new ProjectToolContext(1L, 1, "tester", tempDir,
                1, 50, 50);
        ProjectPathPolicy policy = new ProjectPathPolicy(context);
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/Existing.vue"), "已有内容", StandardCharsets.UTF_8);

        assertThrows(RuntimeException.class,
                () -> policy.assertWritable(tempDir.resolve("src/Another.vue"), "new"));
        assertThrows(RuntimeException.class,
                () -> policy.assertWritable(tempDir.resolve("src/Existing.vue"), "x".repeat(51)));
    }

    private ProjectPathPolicy newPolicy(int maxFiles, long maxTotalBytes, int maxFileSizeBytes) {
        return new ProjectPathPolicy(new ProjectToolContext(1L, 1, "tester", tempDir,
                maxFiles, maxTotalBytes, maxFileSizeBytes));
    }
}
