package com.lian.aicode.core.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Vue 工程构建器。
 *
 * <p>构建命令是后端确定的流程，不交给模型作为工具参数。命令使用参数数组而不是 shell
 * 字符串，Windows 使用 {@code npm.cmd}，并对 build 脚本做白名单检查；这能减少命令注入和
 * WSL/Windows 命令解析差异。构建失败会清理旧 dist，避免继续提供过期页面。</p>
 */
@Slf4j
@Component
public class VueProjectBuilder {

    private static final int MAX_OUTPUT_CHARS = 16_000;

    private final ObjectMapper objectMapper;
    private final Duration installTimeout;
    private final Duration buildTimeout;
    private final Map<Path, ReentrantLock> locks = new ConcurrentHashMap<>();

    public VueProjectBuilder(ObjectMapper objectMapper,
                             @Value("${app.vue-project.install-timeout:10m}") Duration installTimeout,
                             @Value("${app.vue-project.build-timeout:5m}") Duration buildTimeout) {
        this.objectMapper = objectMapper;
        this.installTimeout = safeTimeout(installTimeout, Duration.ofMinutes(10));
        this.buildTimeout = safeTimeout(buildTimeout, Duration.ofMinutes(5));
    }

    public boolean buildProject(String projectPath) {
        return projectPath != null && buildProject(Path.of(projectPath));
    }

    /** 在指定工程根目录执行一次受控的安装和生产构建。 */
    public boolean buildProject(Path projectRoot) {
        if (projectRoot == null) {
            return false;
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        ReentrantLock lock = locks.computeIfAbsent(root, ignored -> new ReentrantLock());
        lock.lock();
        long startedAt = System.nanoTime();
        try {
            if (!isSafeProjectRoot(root)) {
                log.warn("Vue 工程构建拒绝：projectRootInvalid=true");
                return false;
            }
            Path packageJson = root.resolve("package.json");
            if (Files.isSymbolicLink(packageJson)
                    || !Files.isRegularFile(packageJson)
                    || !isSafeBuildScript(packageJson)) {
                log.warn("Vue 工程构建拒绝：reason=package.json 或 build 脚本不符合白名单");
                return false;
            }
            Files.createDirectories(root);
            deleteDirectory(root.resolve("dist"));
            log.info("Vue 工程构建开始：project={}, installTimeoutMs={}, buildTimeoutMs={}",
                    root.getFileName(), installTimeout.toMillis(), buildTimeout.toMillis());
            if (!execute(root, installTimeout, List.of(npmCommand(), "install", "--ignore-scripts",
                    "--no-audit", "--no-fund"), "npm install")) {
                log.warn("Vue 工程构建失败：project={}, step=npm-install", root.getFileName());
                return false;
            }
            if (!execute(root, buildTimeout, List.of(npmCommand(), "run", "build"), "npm run build")) {
                log.warn("Vue 工程构建失败：project={}, step=npm-build", root.getFileName());
                deleteDirectory(root.resolve("dist"));
                return false;
            }
            Path dist = root.resolve("dist");
            boolean success = Files.isDirectory(dist) && Files.isRegularFile(dist.resolve("index.html"));
            if (!success) {
                log.warn("Vue 工程构建失败：project={}, step=verify-dist", root.getFileName());
                deleteDirectory(dist);
                return false;
            }
            log.info("Vue 工程构建完成：project={}, result=成功, durationMs={}",
                    root.getFileName(), elapsedMillis(startedAt));
            return true;
        } catch (Exception exception) {
            deleteDirectory(root.resolve("dist"));
            log.error("Vue 工程构建异常：project={}, reason={}, durationMs={}", root.getFileName(),
                    exception.getClass().getSimpleName(), elapsedMillis(startedAt), exception);
            return false;
        } finally {
            lock.unlock();
            // 不能在解锁后立即从 Map 删除：等待中的线程仍持有旧锁，此时新请求可能创建另一把锁，
            // 导致同一工程并发 npm install/build。工程版本目录数量受业务版本控制，保留锁引用换取正确互斥。
        }
    }

    private boolean isSafeProjectRoot(Path root) {
        try {
            if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) {
                return false;
            }
            root.toRealPath();
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private boolean isSafeBuildScript(Path packageJson) {
        try {
            JsonNode root = objectMapper.readTree(packageJson.toFile());
            JsonNode scripts = root.path("scripts");
            String script = scripts.path("build").asText("").trim();
            // npm run build 会先执行 prebuild/postbuild；生命周期脚本同样属于任意命令入口，必须拒绝。
            // 模板固定使用 vite build，不允许模型借 package.json 把构建改成 node/shell 命令。
            return "vite build".equals(script) && !scripts.has("prebuild") && !scripts.has("postbuild");
        } catch (IOException exception) {
            return false;
        }
    }

    private boolean execute(Path workingDirectory, Duration timeout, List<String> command, String action)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        try (ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> outputFuture = executor.submit(() -> drainOutput(process));
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                outputFuture.cancel(true);
                log.warn("Vue 构建命令超时：action={}, timeoutMs={}", action, timeout.toMillis());
                return false;
            }
            int outputChars;
            try {
                outputChars = outputFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                outputChars = -1;
            }
            int exitCode = process.exitValue();
            log.info("Vue 构建命令结束：action={}, exitCode={}, outputChars={}, result={}",
                    action, exitCode, outputChars, exitCode == 0 ? "成功" : "失败");
            return exitCode == 0;
        }
    }

    private int drainOutput(Process process) throws IOException {
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                count = Math.min(MAX_OUTPUT_CHARS, count + line.length() + 1);
            }
        }
        return count;
    }

    private String npmCommand() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")
                ? "npm.cmd" : "npm";
    }

    private void deleteDirectory(Path directory) {
        // NOFOLLOW_LINKS 让失效符号链接也能被识别并删除，避免失败构建留下可被后续流程误用的入口。
        if (directory == null || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    // walk 默认不跟随符号链接；清理时删除链接自身，避免遗留链接在下一次构建中被使用。
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    log.warn("清理 Vue 构建目录失败：path={}, reason={}", path.getFileName(),
                            exception.getClass().getSimpleName());
                }
            });
        } catch (IOException exception) {
            log.warn("扫描 Vue 构建目录失败：path={}, reason={}", directory.getFileName(),
                    exception.getClass().getSimpleName());
        }
    }

    private Duration safeTimeout(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
