package com.lian.aicode.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Vue 版本继承只复制源文件，不把依赖、构建产物和敏感文件带入新版本。 */
class AppStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void copiesOnlyManagedProjectSourceFiles() throws Exception {
        Path codeRoot = tempDir.resolve("code-output");
        AppStorageService storageService = new AppStorageService(
                codeRoot.toString(), tempDir.resolve("deploy-output").toString());
        Path source = codeRoot.resolve("app/1/v1");
        Path target = codeRoot.resolve("app/1/v2");
        Files.createDirectories(source.resolve("src"));
        Files.writeString(source.resolve("src/App.vue"), "旧页面");
        Files.writeString(source.resolve("package.json"), "{\"scripts\":{\"build\":\"vite build\"}}");
        Files.createDirectories(source.resolve("dist"));
        Files.writeString(source.resolve("dist/index.html"), "构建产物");
        Files.createDirectories(source.resolve("node_modules/pkg"));
        Files.writeString(source.resolve("node_modules/pkg/index.js"), "依赖");
        Files.writeString(source.resolve(".env"), "SECRET=not-for-copy");

        storageService.copyProjectSource(source, target);

        assertTrue(Files.isRegularFile(target.resolve("src/App.vue")));
        assertTrue(Files.isRegularFile(target.resolve("package.json")));
        assertFalse(Files.exists(target.resolve("dist")));
        assertFalse(Files.exists(target.resolve("node_modules")));
        assertFalse(Files.exists(target.resolve(".env")));
    }
}
