package com.lian.aicode.ai.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证增量修改工具必须基于已读取内容进行唯一位置替换，避免误改多个页面节点。 */
class FileModifyToolTest {

    @TempDir
    Path tempDir;

    @Test
    void requiresReadBeforeModify() throws Exception {
        Path file = writeFile("old");
        ProjectToolContext context = newContext();

        String result = new FileModifyTool(context).modifyFile("src/App.vue", "old", "new", 1L);

        assertEquals("修改失败：请先使用 readFile 读取目标文件，再调用 modifyFile", result);
        assertEquals("old", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void replacesOnlyOneUniqueMatchAfterRead() throws Exception {
        Path file = writeFile("old\nkeep");
        ProjectToolContext context = newContext();
        FileReadTool readTool = new FileReadTool(context);
        FileModifyTool modifyTool = new FileModifyTool(context);

        readTool.readFile("src/App.vue", 1L);
        String result = modifyTool.modifyFile("src/App.vue", "old", "new", 1L);

        assertEquals("文件修改成功：src/App.vue", result);
        assertEquals("new\nkeep", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void refusesEmptyOrAmbiguousOldContent() throws Exception {
        Path file = writeFile("old old");
        ProjectToolContext context = newContext();
        FileReadTool readTool = new FileReadTool(context);
        FileModifyTool modifyTool = new FileModifyTool(context);
        readTool.readFile("src/App.vue", 1L);

        assertEquals("修改失败：文件不存在或参数为空",
                modifyTool.modifyFile("src/App.vue", "", "new", 1L));
        assertEquals("修改失败：旧文本匹配到多个位置，请提供更精确的上下文",
                modifyTool.modifyFile("src/App.vue", "old", "new", 1L));
        assertEquals("old old", Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * 2026-09-24 删除整页面任务实测：改后失效重读让同一文件多处修改变成
     * 读改交替加未读拒绝，45 次工具调用耗尽 40 轮上限。写入者对自己刚替换出的
     * 内容持有最新认知，成功后应保留已读标记；唯一匹配检查按磁盘实时内容兜底。
     */
    @Test
    void keepsReadMarkAfterSuccessfulModify() throws Exception {
        Path file = writeFile("alpha\nbeta");
        ProjectToolContext context = newContext();
        FileReadTool readTool = new FileReadTool(context);
        FileModifyTool modifyTool = new FileModifyTool(context);
        readTool.readFile("src/App.vue", 1L);

        assertEquals("文件修改成功：src/App.vue",
                modifyTool.modifyFile("src/App.vue", "alpha", "one", 1L));
        assertEquals("文件修改成功：src/App.vue",
                modifyTool.modifyFile("src/App.vue", "beta", "two", 1L));
        assertEquals("one\ntwo", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void staleOldContentAfterModifyFailsSafelyAndRereadRecovers() throws Exception {
        Path file = writeFile("alpha");
        ProjectToolContext context = newContext();
        FileReadTool readTool = new FileReadTool(context);
        FileModifyTool modifyTool = new FileModifyTool(context);
        readTool.readFile("src/App.vue", 1L);

        modifyTool.modifyFile("src/App.vue", "alpha", "one", 1L);
        // 模型凭过期记忆构造 oldContent：唯一匹配按磁盘实时内容定位，只会安全失败。
        assertEquals("修改失败：未找到需要替换的文本",
                modifyTool.modifyFile("src/App.vue", "alpha", "wrong", 1L));
        assertEquals("one", Files.readString(file, StandardCharsets.UTF_8));
        // 重新读取后可继续正常修改。
        readTool.readFile("src/App.vue", 1L);
        assertEquals("文件修改成功：src/App.vue",
                modifyTool.modifyFile("src/App.vue", "one", "final", 1L));
        assertEquals("final", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void writeFileGrantsReadMarkForImmediateFollowUpModify() throws Exception {
        Path file = tempDir.resolve("src/App.vue");
        Files.createDirectories(file.getParent());
        ProjectToolContext context = newContext();
        FileWriteTool writeTool = new FileWriteTool(context);
        FileModifyTool modifyTool = new FileModifyTool(context);

        assertEquals("文件写入成功：src/App.vue",
                writeTool.writeFile("src/App.vue", "draft", 1L));
        assertEquals("文件修改成功：src/App.vue",
                modifyTool.modifyFile("src/App.vue", "draft", "final", 1L));
        assertEquals("final", Files.readString(file, StandardCharsets.UTF_8));
    }

    private Path writeFile(String content) throws Exception {
        Path file = tempDir.resolve("src/App.vue");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private ProjectToolContext newContext() {
        return new ProjectToolContext(1L, 1, "tester", tempDir, 10, 10_000, 2_000);
    }
}
