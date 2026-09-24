package com.lian.aicode.workflow.service;

import com.lian.aicode.config.AiWorkflowProperties;
import com.lian.aicode.service.AppStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 变更范围质检读取测试：修改轮只把新增/内容变化的文件交给质检，
 * 删除的文件进清单，基线缺失回退全量——避免为存量代码问题重试。
 */
class WorkflowCodeReaderTest {

    @TempDir
    Path tempDir;

    private Path root;
    private WorkflowCodeReader reader;

    @BeforeEach
    void setUp() {
        root = tempDir.resolve("code_output");
        AppStorageService storageService = mock(AppStorageService.class);
        when(storageService.getCodeOutputRoot()).thenReturn(root);
        when(storageService.isInsideManagedRoot(root.resolve("v1"))).thenReturn(true);
        when(storageService.isInsideManagedRoot(root.resolve("v2"))).thenReturn(true);
        AiWorkflowProperties properties = new AiWorkflowProperties();
        reader = new WorkflowCodeReader(storageService, properties);
    }

    @Test
    void changedReaderOnlyIncludesModifiedAndAddedFiles() throws IOException {
        Path baseline = versionDir("v1");
        Path current = versionDir("v2");
        write(current, "src/kept.vue", "same");
        write(baseline, "src/kept.vue", "same");
        write(current, "src/changed.vue", "new-content");
        write(baseline, "src/changed.vue", "old-content");
        write(current, "src/added.vue", "fresh");
        write(baseline, "src/removed.vue", "gone");

        WorkflowCodeReader.CodeSnapshot snapshot = reader.readChanged(baseline, current);

        assertTrue(snapshot.content().contains("src/changed.vue"), "内容变化的文件必须进入质检输入");
        assertTrue(snapshot.content().contains("src/added.vue"), "新增文件必须进入质检输入");
        assertFalse(snapshot.content().contains("## 文件: src/kept.vue"),
                "未变更文件不应进入质检输入（存量问题不应触发重试）");
        assertTrue(snapshot.content().contains("src/removed.vue"), "删除的文件应出现在删除清单");
        assertTrue(snapshot.content().contains("删除的文件"), "应输出删除清单标题");
        // 只有 changed 与 added 进入内容区；removed 仅进清单，kept 被排除。
        assertEqualsFiles(2, snapshot.fileCount());
    }

    @Test
    void missingBaselineFallsBackToFullRead() throws IOException {
        Path current = versionDir("v2");
        write(current, "src/a.vue", "a");
        write(current, "src/b.vue", "b");

        WorkflowCodeReader.CodeSnapshot snapshot =
                reader.readChanged(root.resolve("v0-missing"), current);

        assertTrue(snapshot.content().contains("src/a.vue"));
        assertTrue(snapshot.content().contains("src/b.vue"));
        assertFalse(snapshot.content().contains("删除的文件"), "全量回退不应有删除清单");
        assertEqualsFiles(2, snapshot.fileCount());
    }

    @Test
    void nullBaselineFallsBackToFullRead() throws IOException {
        Path current = versionDir("v2");
        write(current, "index.html", "<html></html>");

        WorkflowCodeReader.CodeSnapshot snapshot = reader.readChanged(null, current);

        assertTrue(snapshot.content().contains("index.html"));
    }

    private Path versionDir(String name) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir.resolve("src"));
        return dir;
    }

    private void write(Path base, String relative, String content) throws IOException {
        Path file = base.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void assertEqualsFiles(int expected, int actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
