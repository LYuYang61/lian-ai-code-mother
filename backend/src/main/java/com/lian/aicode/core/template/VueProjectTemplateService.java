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
    private static final List<String> TEMPLATE_FILES = List.of(
            ".gitignore", "package.json", "index.html", "vite.config.js",
            "src/main.js", "src/App.vue", "src/style.css", "src/router/index.js",
            "src/pages/HomePage.vue"
    );

    public void prepareDefaultTemplate(Path projectRoot, Long appId, Integer versionNo, String actorAccount) {
        Path normalizedRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        if (normalizedRoot == null) {
            throw new IllegalArgumentException("Vue 工程目录不能为空");
        }
        try {
            Files.createDirectories(normalizedRoot);
            for (String file : TEMPLATE_FILES) {
                Path target = normalizedRoot.resolve(file).normalize();
                if (!target.startsWith(normalizedRoot)) {
                    throw new IOException("模板路径越界");
                }
                Files.createDirectories(target.getParent());
                // 新版本可能已经继承上一可用版本；模板只补齐缺失文件，不能覆盖用户上一轮生成的页面。
                if (Files.exists(target)) {
                    continue;
                }
                ClassPathResource resource = new ClassPathResource(TEMPLATE_ROOT + file);
                try (InputStream inputStream = resource.getInputStream()) {
                    Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            log.info("准备 Vue 工程模板：actor={}, appId={}, version={}, template=default, result=成功",
                    actorAccount, appId, versionNo);
        } catch (IOException exception) {
            log.error("准备 Vue 工程模板失败：actor={}, appId={}, version={}, reason={}",
                    actorAccount, appId, versionNo, exception.getClass().getSimpleName(), exception);
            throw new IllegalStateException("Vue 工程模板准备失败", exception);
        }
    }
}
