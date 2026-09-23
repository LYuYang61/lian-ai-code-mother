package com.lian.aicode.service;

import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.service.impl.ProjectDownloadServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证下载 ZIP 只包含受控源文件，并过滤构建产物、临时文件和敏感配置。 */
class ProjectDownloadServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void writesOnlySafeSourceFiles() throws Exception {
        Path codeRoot = tempDir.resolve("code-output");
        Path project = codeRoot.resolve("app/1/v1");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/index.html"), "<html></html>");
        Files.writeString(project.resolve("README.md"), "readme");
        Files.writeString(project.resolve(".env"), "SECRET=hidden");
        Files.writeString(project.resolve("debug.log"), "not for download");
        Files.writeString(project.resolve("cache.tmp"), "not for download");
        Files.writeString(project.resolve(".DS_Store"), "not for download");
        Files.writeString(project.resolve("server.pem"), "not for download");
        Files.createDirectories(project.resolve("dist"));
        Files.writeString(project.resolve("dist/index.html"), "build artifact");
        Files.createDirectories(project.resolve("node_modules/demo"));
        Files.writeString(project.resolve("node_modules/demo/index.js"), "dependency");

        ProjectDownloadService service = new ProjectDownloadServiceImpl(
                new AppStorageService(codeRoot.toString(), tempDir.resolve("deploy-output").toString()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ProjectDownloadService.DownloadResult result = service.writeZip(project, output);

        Set<String> entries = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(output.toByteArray()))) {
            for (java.util.zip.ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                entries.add(entry.getName());
            }
        }
        assertEquals(2, result.fileCount());
        assertEquals(Set.of("README.md", "src/index.html"), entries);
    }

    @Test
    void rejectsDirectoryOutsideManagedRoot() throws Exception {
        Path codeRoot = tempDir.resolve("code-output");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        ProjectDownloadService service = new ProjectDownloadServiceImpl(
                new AppStorageService(codeRoot.toString(), tempDir.resolve("deploy-output").toString()));

        assertThrows(BusinessException.class,
                () -> service.writeZip(outside, new ByteArrayOutputStream()));
        assertFalse(Files.exists(codeRoot));
        assertTrue(Files.isDirectory(outside));
    }
}
