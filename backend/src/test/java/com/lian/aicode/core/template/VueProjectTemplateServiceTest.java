package com.lian.aicode.core.template;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证模板只补齐缺失文件，不覆盖上一版本继承下来的业务页面。 */
class VueProjectTemplateServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void templateDoesNotOverwriteExistingFiles() throws Exception {
        VueProjectTemplateService service = new VueProjectTemplateService();
        Path projectRoot = tempDir.resolve("project");
        service.prepareDefaultTemplate(projectRoot, 1L, 1, "tester");
        Path appFile = projectRoot.resolve("src/App.vue");
        Files.writeString(appFile, "继承的业务页面");

        service.prepareDefaultTemplate(projectRoot, 1L, 2, "tester");

        assertEquals("继承的业务页面", Files.readString(appFile));
        assertTrue(Files.isRegularFile(projectRoot.resolve("package.json")));
    }

    /**
     * 2026-09-24 实测：删除未被引用的 HomePage.vue 后，下一版迭代被模板"补齐"悄悄加回，
     * 重新变成死代码。业务起点文件只允许在全新工程放置，迭代轮删除是合法演进。
     */
    @Test
    void starterFilesAreNotResurrectedAfterDeletion() throws Exception {
        VueProjectTemplateService service = new VueProjectTemplateService();
        Path projectRoot = tempDir.resolve("project");
        service.prepareDefaultTemplate(projectRoot, 1L, 1, "tester");

        Files.delete(projectRoot.resolve("src/pages/HomePage.vue"));
        service.prepareDefaultTemplate(projectRoot, 1L, 2, "tester");
        assertFalse(Files.exists(projectRoot.resolve("src/pages/HomePage.vue")),
                "迭代轮被删除的业务起点文件不能复活");

        Files.delete(projectRoot.resolve("package.json"));
        service.prepareDefaultTemplate(projectRoot, 1L, 3, "tester");
        assertTrue(Files.exists(projectRoot.resolve("package.json")),
                "构建必需文件每轮仍要补齐");
    }

    @Test
    void freshProjectGetsAllStarterFiles() throws Exception {
        VueProjectTemplateService service = new VueProjectTemplateService();
        Path projectRoot = tempDir.resolve("project");

        service.prepareDefaultTemplate(projectRoot, 1L, 1, "tester");

        assertTrue(Files.isRegularFile(projectRoot.resolve("src/router/index.js")));
        assertTrue(Files.isRegularFile(projectRoot.resolve("src/pages/HomePage.vue")),
                "模板 router 以 HomePage 作首页，全新工程必须整体放置起点文件");
    }
}
