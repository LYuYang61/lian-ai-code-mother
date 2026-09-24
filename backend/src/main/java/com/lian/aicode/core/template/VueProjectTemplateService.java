package com.lian.aicode.core.template;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Vue 工程基础模板服务。
 *
 * <p>模板把可运行的 package.json、Vite 配置、路由和入口固定下来，模型只需要围绕用户需求
 * 修改业务文件，降低从空目录生成导致构建失败的概率。</p>
 */
@Slf4j
@Service
public class VueProjectTemplateService {

    private static final String TEMPLATE_ROOT = "vue-template/default/";
    /** 构建必需文件：每轮迭代都补齐，防止模型删坏工程骨架。 */
    private static final List<String> ESSENTIAL_FILES = List.of(
            ".gitignore", "package.json", "index.html", "vite.config.js", "src/main.js");
    /**
     * 业务起点文件：互相引用（router 引用 HomePage 作首页），只能作为全新工程的起点整体放置。
     * 迭代轮删除它们是合法演进，不能每轮复活——2026-09-24 实测：删除未被引用的
     * HomePage.vue 后，下一版被模板"补齐"悄悄加回，重新变成死代码。
     */
    private static final List<String> STARTER_FILES = List.of(
            "src/App.vue", "src/style.css", "src/router/index.js", "src/pages/HomePage.vue");

    public void prepareDefaultTemplate(Path projectRoot, Long appId, Integer versionNo, String actorAccount) {
        Path normalizedRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        if (normalizedRoot == null) {
            throw new IllegalArgumentException("Vue 工程目录不能为空");
        }
        try {
            Files.createDirectories(normalizedRoot);
            boolean freshProject = STARTER_FILES.stream()
                    .noneMatch(file -> Files.exists(normalizedRoot.resolve(file)));
            copyMissing(normalizedRoot, ESSENTIAL_FILES);
            if (freshProject) {
                copyMissing(normalizedRoot, STARTER_FILES);
            }
            log.info("准备 Vue 工程模板：actor={}, appId={}, version={}, template=default, result=成功",
                    actorAccount, appId, versionNo);
        } catch (IOException exception) {
            log.error("准备 Vue 工程模板失败：actor={}, appId={}, version={}, reason={}",
                    actorAccount, appId, versionNo, exception.getClass().getSimpleName(), exception);
            throw new IllegalStateException("Vue 工程模板准备失败", exception);
        }
    }

    private void copyMissing(Path normalizedRoot, List<String> files) throws IOException {
        for (String file : files) {
            Path target = normalizedRoot.resolve(file).normalize();
            if (!target.startsWith(normalizedRoot)) {
                throw new IOException("模板路径越界");
            }
            // 新版本可能已经继承上一可用版本；模板只补齐缺失文件，不能覆盖用户上一轮生成的页面。
            if (Files.exists(target)) {
                continue;
            }
            Files.createDirectories(target.getParent());
            ClassPathResource resource = new ClassPathResource(TEMPLATE_ROOT + file);
            try (InputStream inputStream = resource.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
