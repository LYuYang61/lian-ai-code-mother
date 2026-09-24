package com.lian.aicode.core.saver;

import com.lian.aicode.ai.model.MultiFileCodeResult;
import com.lian.aicode.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodeFileSaverTest {

    @TempDir
    Path tempDir;

    @Test
    void savesFixedFilesUnderConfiguredRoot() throws Exception {
        CodeFileSaverExecutor executor = new CodeFileSaverExecutor(tempDir);
        MultiFileCodeResult result = new MultiFileCodeResult();
        result.setHtmlCode("<html></html>");
        result.setCssCode("body { margin: 0; }");
        result.setJsCode("console.log('ok');");

        Path outputDirectory = executor.executeSaver(result,
                        com.lian.aicode.model.enums.CodeGenTypeEnum.MULTI_FILE)
                .toPath();

        assertEquals(tempDir.toAbsolutePath().normalize(), outputDirectory.getParent());
        assertEquals("<html></html>", Files.readString(outputDirectory.resolve("index.html")));
        assertEquals("body { margin: 0; }", Files.readString(outputDirectory.resolve("style.css")));
        assertEquals("console.log('ok');", Files.readString(outputDirectory.resolve("script.js")));
    }

    @Test
    void rejectsResultWithoutRequiredHtml() {
        CodeFileSaverExecutor executor = new CodeFileSaverExecutor(tempDir);
        MultiFileCodeResult result = new MultiFileCodeResult();

        assertThrows(BusinessException.class, () -> executor.executeSaver(
                result, com.lian.aicode.model.enums.CodeGenTypeEnum.MULTI_FILE));
    }

    @Test
    void replacesExistingDirectoryWithoutLeavingOldFiles() throws Exception {
        CodeFileSaverExecutor executor = new CodeFileSaverExecutor(tempDir);
        Path target = tempDir.resolve("app/1/v2");
        Files.createDirectories(target);
        Files.writeString(target.resolve("index.html"), "old");
        Files.writeString(target.resolve("obsolete.txt"), "must be removed");

        MultiFileCodeResult result = new MultiFileCodeResult();
        result.setHtmlCode("<html>new</html>");
        result.setCssCode("body { color: red; }");
        result.setJsCode("console.log('new');");

        Path replaced = executor.executeSaverReplacing(
                        result, com.lian.aicode.model.enums.CodeGenTypeEnum.MULTI_FILE, target)
                .toPath();

        org.junit.jupiter.api.Assertions.assertEquals(target, replaced);
        org.junit.jupiter.api.Assertions.assertEquals("<html>new</html>",
                Files.readString(target.resolve("index.html")));
        org.junit.jupiter.api.Assertions.assertEquals("body { color: red; }",
                Files.readString(target.resolve("style.css")));
        org.junit.jupiter.api.Assertions.assertEquals("console.log('new');",
                Files.readString(target.resolve("script.js")));
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(target.resolve("obsolete.txt")));
    }
}
