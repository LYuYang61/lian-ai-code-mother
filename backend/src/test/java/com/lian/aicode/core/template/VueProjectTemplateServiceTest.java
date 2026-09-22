package com.lian.aicode.core.template;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
