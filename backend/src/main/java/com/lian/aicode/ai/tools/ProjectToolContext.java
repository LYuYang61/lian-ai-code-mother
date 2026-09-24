package com.lian.aicode.ai.tools;

import lombok.AccessLevel;
import lombok.Getter;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次 Vue 工程生成的受控上下文。
 *
 * <p>工具实例按生成任务创建，而不是作为全局单例持有可变目录；这样并发生成不同应用或
 * 不同版本时不会因为 ThreadLocal 或全局字段串写文件。</p>
 */
@Getter
public final class ProjectToolContext {

    private final Long appId;
    private final Integer versionNo;
    private final String actorAccount;
    private final Path projectRoot;
    private final int maxFiles;
    private final long maxTotalBytes;
    private final int maxFileSizeBytes;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    /** 记录本轮已成功读取的真实文件，供 modifyFile 强制执行“先读后改”。 */
    @Getter(AccessLevel.NONE)
    private final Set<Path> readFiles = ConcurrentHashMap.newKeySet();

    public ProjectToolContext(Long appId, Integer versionNo, String actorAccount, Path projectRoot,
                              int maxFiles, long maxTotalBytes, int maxFileSizeBytes) {
        if (appId == null || appId <= 0 || versionNo == null || versionNo <= 0) {
            throw new IllegalArgumentException("应用或版本参数无效");
        }
        this.appId = appId;
        this.versionNo = versionNo;
        this.actorAccount = actorAccount == null || actorAccount.isBlank() ? "unknown" : actorAccount;
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.maxFiles = Math.max(maxFiles, 1);
        this.maxTotalBytes = Math.max(maxTotalBytes, 1L);
        this.maxFileSizeBytes = Math.max(maxFileSizeBytes, 1);
    }

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void markFileRead(Path path) {
        if (path != null) {
            readFiles.add(normalize(path));
        }
    }

    public boolean hasReadFile(Path path) {
        return path != null && readFiles.contains(normalize(path));
    }

    public void invalidateFileRead(Path path) {
        if (path != null) {
            readFiles.remove(normalize(path));
        }
    }

    private Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
