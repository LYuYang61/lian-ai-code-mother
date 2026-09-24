package com.lian.aicode.ai.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证用户取消后文件工具会明确通知模型停止，而不是伪装成普通文件错误。 */
class ProjectToolCancellationTest {

    @TempDir
    Path tempDir;

    @Test
    void writeFileReturnsCancellationMessageBeforeTouchingDisk() throws Exception {
        ProjectToolContext context = cancelledContext();
        FileWriteTool tool = new FileWriteTool(context);

        String result = tool.writeFile("src/App.vue", "<template />", 1L);

        assertEquals("生成任务已被用户取消，请停止调用文件工具", result);
        assertFalse(Files.exists(tempDir.resolve("src/App.vue")));
    }

    @Test
    void modifyFileReturnsCancellationMessageWithoutChangingFile() throws Exception {
        Path file = tempDir.resolve("src/App.vue");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "old", StandardCharsets.UTF_8);
        ProjectToolContext context = cancelledContext();
        FileModifyTool tool = new FileModifyTool(context);

        String result = tool.modifyFile("src/App.vue", "old", "new", 1L);

        assertEquals("生成任务已被用户取消，请停止调用文件工具", result);
        assertEquals("old", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void readDeleteDirectoryAndExitToolsStopAfterCancellation() throws Exception {
        Path file = tempDir.resolve("src/App.vue");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "content", StandardCharsets.UTF_8);
        ProjectToolContext context = cancelledContext();

        assertEquals("生成任务已被用户取消，请停止调用文件工具",
                new FileReadTool(context).readFile("src/App.vue", 1L));
        assertEquals("生成任务已被用户取消，请停止调用文件工具",
                new FileDirReadTool(context).readDir("", 1L));
        assertEquals("生成任务已被用户取消，请停止调用文件工具",
                new FileDeleteTool(context).deleteFile("src/App.vue", 1L));
        assertEquals("生成任务已被用户取消，请停止调用文件工具",
                new ExitTool(context).exit());
        assertTrue(Files.exists(file));
    }

    private ProjectToolContext cancelledContext() {
        ProjectToolContext context = new ProjectToolContext(1L, 1, "tester", tempDir,
                10, 10_000, 2_000);
        context.cancel();
        return context;
    }
}
