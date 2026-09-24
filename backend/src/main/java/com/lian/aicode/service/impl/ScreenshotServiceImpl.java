package com.lian.aicode.service.impl;

import cn.hutool.core.img.ImgUtil;
import com.lian.aicode.config.ScreenshotProperties;
import com.lian.aicode.manager.OssManager;
import com.lian.aicode.mapper.AppMapper;
import com.lian.aicode.mapper.AppVersionMapper;
import com.lian.aicode.model.entity.App;
import com.lian.aicode.model.entity.AppVersion;
import com.lian.aicode.model.enums.AppDeploymentStatusEnum;
import com.lian.aicode.model.enums.AppVersionStatusEnum;
import com.lian.aicode.model.enums.AppVisibilityEnum;
import com.lian.aicode.service.ScreenshotService;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 应用封面截图服务。
 *
 * <p>截图任务使用有界队列，且每个任务创建并销毁自己的 ChromeDriver。这样不会共享非线程安全的
 * WebDriver，也不会让并发部署无限制地启动浏览器。截图失败只影响封面，不回滚已经成功的部署。</p>
 */
@Slf4j
@Service
public class ScreenshotServiceImpl implements ScreenshotService {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final ScreenshotProperties properties;
    private final OssManager ossManager;
    private final AppMapper appMapper;
    private final AppVersionMapper appVersionMapper;
    private final String deployPublicBaseUrl;
    private final String previewPublicBaseUrl;
    private final ThreadPoolExecutor executor;
    private final ConcurrentMap<String, Boolean> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> retryAfter = new ConcurrentHashMap<>();

    public ScreenshotServiceImpl(ScreenshotProperties properties,
                                 OssManager ossManager,
                                 AppMapper appMapper,
                                 AppVersionMapper appVersionMapper,
                                 @Value("${app.deploy.public-base-url:http://localhost:8123/api/site}") String deployPublicBaseUrl,
                                 @Value("${app.preview.public-base-url:http://localhost:8123/api/preview}") String previewPublicBaseUrl) {
        this.properties = properties;
        this.ossManager = ossManager;
        this.appMapper = appMapper;
        this.appVersionMapper = appVersionMapper;
        this.deployPublicBaseUrl = deployPublicBaseUrl;
        this.previewPublicBaseUrl = previewPublicBaseUrl;
        int maxConcurrent = Math.max(properties.getMaxConcurrent(), 1);
        int queueCapacity = Math.max(properties.getQueueCapacity(), 1);
        AtomicInteger threadSequence = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "app-screenshot-" + threadSequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
        this.executor = new ThreadPoolExecutor(maxConcurrent, maxConcurrent, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), threadFactory, new ThreadPoolExecutor.AbortPolicy());
    }

    @PostConstruct
    public void initialize() {
        if (properties.isEnabled()) {
            log.info("初始化应用封面截图服务：enabled=true, maxConcurrent={}, queueCapacity={}, ossAvailable={}",
                    executor.getMaximumPoolSize(), executor.getQueue().remainingCapacity() + executor.getQueue().size(),
                    ossManager.isAvailable());
        } else {
            log.info("应用封面截图服务未启用：enabled=false, result=基础部署不受影响");
        }
    }

    @Override
    public void submit(Long appId, Integer versionNo, String webUrl, String trigger, String actorAccount) {
        if (!properties.isEnabled() || !ossManager.isAvailable()) {
            return;
        }
        if (!isSafeUrl(webUrl)) {
            log.warn("封面截图任务被拒绝：actor={}, appId={}, version={}, trigger={}, reason=URL不在允许范围",
                    safeActor(actorAccount), appId, versionNo, trigger);
            return;
        }
        String taskKey = taskKey(appId, versionNo);
        if (isRetryCoolingDown(taskKey)) {
            return;
        }
        if (inFlight.putIfAbsent(taskKey, Boolean.TRUE) != null) {
            log.info("封面截图任务去重：actor={}, appId={}, version={}, trigger={}, result=已有任务",
                    safeActor(actorAccount), appId, versionNo, trigger);
            return;
        }
        try {
            executor.execute(() -> runTask(appId, versionNo, webUrl, trigger, actorAccount, taskKey));
            log.info("提交封面截图任务：actor={}, appId={}, version={}, trigger={}, result=成功",
                    safeActor(actorAccount), appId, versionNo, trigger);
        } catch (RejectedExecutionException exception) {
            inFlight.remove(taskKey);
            log.warn("提交封面截图任务失败：actor={}, appId={}, version={}, trigger={}, reason=队列已满",
                    safeActor(actorAccount), appId, versionNo, trigger);
        }
    }

    @Override
    public void submitIfMissing(Long appId, Integer versionNo, String webUrl, String trigger, String actorAccount) {
        if (appId == null || versionNo == null) {
            return;
        }
        try {
            App app = appMapper.selectOneById(appId);
            if (app == null || !versionNo.equals(app.getCurrentVersion()) || !needsCover(app, versionNo)) {
                return;
            }
            String reachableUrl = resolveReachableScreenshotUrl(app, versionNo, webUrl);
            if (reachableUrl == null) {
                return;
            }
            submit(appId, versionNo, reachableUrl, trigger, actorAccount);
        } catch (RuntimeException exception) {
            // 封面是部署后的可选补偿能力，不能把数据库短暂故障传播给已完成的主业务。
            log.warn("检查并提交封面截图任务失败：actor={}, appId={}, version={}, trigger={}, reason={}",
                    safeActor(actorAccount), appId, versionNo, trigger, exception.getClass().getSimpleName());
        }
    }

    @Override
    public void deleteCover(String coverUrl, Long appId, String actorAccount) {
        if (!StringUtils.hasText(coverUrl) || !ossManager.isAvailable()) {
            return;
        }
        try {
            boolean deleted = ossManager.deleteByUrl(coverUrl, appId);
            log.info("清理应用封面对象：actor={}, appId={}, result={}", safeActor(actorAccount), appId,
                    deleted ? "成功" : "跳过");
        } catch (RuntimeException exception) {
            log.error("清理应用封面对象失败：actor={}, appId={}, reason={}", safeActor(actorAccount), appId,
                    exception.getClass().getSimpleName(), exception);
            throw exception;
        }
    }

    private void runTask(Long appId, Integer versionNo, String webUrl, String trigger,
                         String actorAccount, String taskKey) {
        long startedAt = System.nanoTime();
        Path taskDirectory = null;
        WebDriver driver = null;
        String uploadedCover = null;
        try {
            App appBefore = appMapper.selectOneById(appId);
            if (appBefore == null || !versionNo.equals(appBefore.getCurrentVersion())) {
                log.info("封面截图任务跳过：actor={}, appId={}, version={}, trigger={}, result=版本已过期",
                        safeActor(actorAccount), appId, versionNo, trigger);
                return;
            }
            String oldCover = appBefore.getCover();
            taskDirectory = createTaskDirectory(appId, versionNo);
            driver = createDriver();
            driver.get(webUrl);
            waitForPageLoad(driver);

            Path original = taskDirectory.resolve("page.png");
            Path compressed = taskDirectory.resolve("page.jpg");
            Files.write(original, ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES));
            ImgUtil.compress(original.toFile(), compressed.toFile(), 0.3f);

            String objectKey = "app-covers/" + appId + "/v" + versionNo + "/" + UUID.randomUUID() + ".jpg";
            uploadedCover = ossManager.upload(objectKey, compressed);
            if (!StringUtils.hasText(uploadedCover)) {
                throw new IOException("阿里云 OSS 未返回封面地址");
            }
            String newCover = uploadedCover;
            int updated = appMapper.updateCoverIfCurrentVersion(appId, versionNo,
                    normalizeCover(oldCover), newCover);
            if (updated <= 0) {
                ossManager.deleteByUrl(newCover, appId);
                uploadedCover = null;
                log.warn("保存应用封面失败：actor={}, appId={}, version={}, trigger={}, reason=版本或封面已变化",
                        safeActor(actorAccount), appId, versionNo, trigger);
                return;
            }
            // 数据库已经接管该对象的所有权；后续旧封面清理失败时绝不能删除刚写入的新封面。
            uploadedCover = null;
            if (StringUtils.hasText(oldCover) && !oldCover.equals(newCover)) {
                try {
                    deleteCover(oldCover, appId, actorAccount);
                } catch (RuntimeException cleanupException) {
                    log.warn("清理旧应用封面失败：actor={}, appId={}, version={}, reason={}, result=保留新封面",
                            safeActor(actorAccount), appId, versionNo,
                            cleanupException.getClass().getSimpleName());
                }
            }
            log.info("应用封面生成完成：actor={}, appId={}, version={}, trigger={}, result=成功, durationMs={}",
                    safeActor(actorAccount), appId, versionNo, trigger, elapsedMillis(startedAt));
            retryAfter.remove(taskKey);
        } catch (Exception exception) {
            if (StringUtils.hasText(uploadedCover)) {
                try {
                    ossManager.deleteByUrl(uploadedCover, appId);
                } catch (RuntimeException cleanupException) {
                    log.warn("清理失败封面对象失败：appId={}, version={}, reason={}", appId, versionNo,
                            cleanupException.getClass().getSimpleName());
                }
            }
            log.warn("应用封面生成失败：actor={}, appId={}, version={}, trigger={}, reason={}, durationMs={}",
                    safeActor(actorAccount), appId, versionNo, trigger,
                    exception.getClass().getSimpleName(), elapsedMillis(startedAt));
            retryAfter.put(taskKey, System.currentTimeMillis()
                    + safeDuration(properties.getFailureRetryDelay(), Duration.ofMinutes(5)).toMillis());
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (RuntimeException closeException) {
                    log.warn("关闭截图浏览器失败：appId={}, version={}, reason={}", appId, versionNo,
                            closeException.getClass().getSimpleName());
                }
            }
            deleteRecursively(taskDirectory);
            inFlight.remove(taskKey);
        }
    }

    private WebDriver createDriver() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--disable-gpu", "--disable-extensions",
                "--window-size=" + properties.getViewportWidth() + ","
                        + properties.getViewportHeight());
        // Windows 桌面 Chrome 不需要关闭沙箱；Linux/Docker 再使用教程常见的兼容参数。
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            options.addArguments("--no-sandbox", "--disable-dev-shm-usage");
        }
        ChromeDriver driver = new ChromeDriver(options);
        driver.manage().timeouts().pageLoadTimeout(safeDuration(properties.getPageLoadTimeout(), Duration.ofSeconds(30)));
        return driver;
    }

    private void waitForPageLoad(WebDriver driver) {
        Duration timeout = safeDuration(properties.getWaitTimeout(), Duration.ofSeconds(15));
        new WebDriverWait(driver, timeout).until(current -> {
            Object state = ((JavascriptExecutor) current).executeScript("return document.readyState");
            return "complete".equals(state);
        });
    }

    @Scheduled(fixedDelayString = "${app.screenshot.retry-delay-ms:1800000}",
            initialDelayString = "${app.screenshot.retry-initial-delay-ms:60000}")
    public void retryMissingCovers() {
        if (!properties.isEnabled() || !ossManager.isAvailable()) {
            return;
        }
        long now = System.currentTimeMillis();
        retryAfter.entrySet().removeIf(entry -> entry.getValue() <= now);
        try {
            var candidates = appMapper.selectListByQuery(QueryWrapper.create()
                    .gt("current_version", 0).eq("is_delete", 0).orderBy("update_time", false));
            int submitted = 0;
            for (App app : candidates) {
                if (submitted >= Math.max(properties.getRetryBatchSize(), 1)
                        || !needsCover(app, app.getCurrentVersion())
                        || !isPubliclyReachable(app)) {
                    continue;
                }
                AppVersion version = appVersionMapper.selectOneByQuery(QueryWrapper.create()
                        .eq("app_id", app.getId()).eq("version_no", app.getCurrentVersion()));
                if (version == null || !AppVersionStatusEnum.READY.getValue().equals(version.getStatus())) {
                    continue;
                }
                String url = AppDeploymentStatusEnum.DEPLOYED.getValue().equals(app.getDeploymentStatus())
                        && StringUtils.hasText(app.getDeployKey())
                        ? buildDeployUrl(app.getDeployKey())
                        : buildPreviewUrl(app.getId(), app.getCurrentVersion());
                submitIfMissing(app.getId(), app.getCurrentVersion(), url, "scheduled-retry", "<scheduler>");
                submitted++;
            }
            if (submitted > 0) {
                log.info("补偿提交缺失应用封面：count={}, result=完成", submitted);
            }
        } catch (RuntimeException exception) {
            log.warn("补偿提交应用封面失败：reason={}", exception.getClass().getSimpleName(), exception);
        }
    }

    @Scheduled(fixedDelayString = "${app.screenshot.cleanup-delay-ms:3600000}",
            initialDelayString = "${app.screenshot.cleanup-initial-delay-ms:300000}")
    public void cleanupExpiredTempFiles() {
        Path root = tempRoot();
        if (root == null || !Files.isDirectory(root) || Files.isSymbolicLink(root)) {
            return;
        }
        Instant deadline = Instant.now().minus(safeDuration(properties.getTempFileTtl(), Duration.ofHours(2)));
        int deleted = 0;
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                if (!Files.isDirectory(child) || Files.isSymbolicLink(child)) {
                    continue;
                }
                Instant modified = Files.getLastModifiedTime(child).toInstant();
                if (modified.isBefore(deadline)) {
                    deleteRecursively(child);
                    deleted++;
                }
            }
            if (deleted > 0) {
                log.info("清理过期截图临时目录：count={}, result=成功", deleted);
            }
        } catch (IOException exception) {
            log.warn("清理截图临时目录失败：reason={}", exception.getClass().getSimpleName(), exception);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        log.info("关闭应用封面截图线程池：result=完成");
    }

    private Path createTaskDirectory(Long appId, Integer versionNo) throws IOException {
        Path root = tempRoot();
        if (root == null) {
            throw new IOException("截图临时目录配置无效");
        }
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) {
            throw new IOException("截图临时根目录不能是符号链接");
        }
        return Files.createTempDirectory(root, "app-" + appId + "-v" + versionNo + "-");
    }

    private Path tempRoot() {
        if (!StringUtils.hasText(properties.getTempRoot())) {
            return null;
        }
        Path root = Path.of(properties.getTempRoot()).toAbsolutePath().normalize();
        return root.getNameCount() == 0 ? null : root;
    }

    private boolean isSafeUrl(String webUrl) {
        if (!StringUtils.hasText(webUrl)) {
            return false;
        }
        try {
            var uri = java.net.URI.create(webUrl.trim());
            if (!ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase())) {
                return false;
            }
            String host = uri.getHost();
            if (!StringUtils.hasText(host)) {
                return false;
            }
            return Arrays.stream(properties.getAllowedHosts().split(","))
                    .map(String::trim).filter(StringUtils::hasText)
                    .anyMatch(allowed -> allowed.equalsIgnoreCase(host));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String buildDeployUrl(String deployKey) {
        return deployPublicBaseUrl.replaceAll("/+$", "") + "/" + deployKey + "/";
    }

    private String buildPreviewUrl(Long appId, Integer versionNo) {
        return previewPublicBaseUrl.replaceAll("/+$", "") + "/" + appId + "/" + versionNo + "/";
    }

    /** 只自动替换本服务生成的旧封面，管理员手工填写的外部图片地址保持不动。 */
    private boolean needsCover(App app, Integer versionNo) {
        if (!StringUtils.hasText(app.getCover())) {
            return true;
        }
        String managedPrefix = "/app-covers/" + app.getId() + "/v";
        if (!app.getCover().contains(managedPrefix)) {
            return false;
        }
        return !app.getCover().contains(managedPrefix + versionNo + "/");
    }

    private boolean isPubliclyReachable(App app) {
        return AppVisibilityEnum.PUBLIC.getValue().equals(app.getVisibility())
                || AppDeploymentStatusEnum.DEPLOYED.getValue().equals(app.getDeploymentStatus());
    }

    /**
     * 按应用状态解析截图浏览器真正可达的目标地址，返回 null 表示本轮放弃截图。
     *
     * <p>截图浏览器没有用户 Session：public 应用的 preview 地址可匿名访问，信任调用方 URL；
     * private 应用的 preview 需要鉴权，只有部署目录已经切到目标版本（deployedVersion 等于
     * versionNo）时才能改用公开部署地址。部署目录还停留在旧版本时，preview 会截到 401 错误页、
     * 部署地址会截到旧版本页面，都只能跳过，等部署动作以 deploy 触发补上正确封面。
     * 2026-09-24 实测：private+已部署应用生成新版后按 preview 地址截到 401 错误页并上传。</p>
     */
    private String resolveReachableScreenshotUrl(App app, Integer versionNo, String fallbackUrl) {
        if (AppVisibilityEnum.PUBLIC.getValue().equals(app.getVisibility())) {
            return fallbackUrl;
        }
        return isDeployedToVersion(app, versionNo) ? buildDeployUrl(app.getDeployKey()) : null;
    }

    /** private 应用只有部署目录已切到目标版本时，公开部署地址的页面内容才与该版本一致。 */
    static boolean isDeployedToVersion(App app, Integer versionNo) {
        return AppDeploymentStatusEnum.DEPLOYED.getValue().equals(app.getDeploymentStatus())
                && StringUtils.hasText(app.getDeployKey())
                && versionNo.equals(app.getDeployedVersion());
    }

    private String taskKey(Long appId, Integer versionNo) {
        return appId + ":" + versionNo;
    }

    private boolean isRetryCoolingDown(String taskKey) {
        Long retryAt = retryAfter.get(taskKey);
        if (retryAt == null) {
            return false;
        }
        if (retryAt <= System.currentTimeMillis()) {
            retryAfter.remove(taskKey, retryAt);
            return false;
        }
        return true;
    }

    private String normalizeCover(String cover) {
        return StringUtils.hasText(cover) ? cover : null;
    }

    private String safeActor(String actorAccount) {
        return StringUtils.hasText(actorAccount) ? actorAccount : "<unknown>";
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private Duration safeDuration(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exception) throws IOException {
                    if (exception != null) {
                        throw exception;
                    }
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            log.warn("清理截图临时文件失败：reason={}", exception.getClass().getSimpleName());
        }
    }
}
