package com.lian.aicode.service.impl;

import com.lian.aicode.model.entity.App;
import com.lian.aicode.model.enums.AppDeploymentStatusEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * private 应用截图可达性判据测试。2026-09-24 实测：private+已部署应用生成新版本后，
 * 截图服务仍按 preview 地址提交，无 Session 的浏览器截到 401 错误页并上传成封面；
 * 部署目录还停留在旧版本时用部署地址同样错误（内容是旧版本页面）。修复后 private
 * 应用只有部署目录已切到目标版本时才允许截图。
 */
class ScreenshotReachabilityTest {

    @Test
    void privateAppIsReachableOnlyWhenDeployedToTargetVersion() {
        assertTrue(ScreenshotServiceImpl.isDeployedToVersion(deployedApp(3, "key-1"), 3),
                "部署目录已切到目标版本时可用公开部署地址截图");
    }

    @Test
    void staleDeployedVersionIsNotReachable() {
        assertFalse(ScreenshotServiceImpl.isDeployedToVersion(deployedApp(2, "key-1"), 3),
                "部署目录还是旧版本：preview 会 401、部署地址内容错版本，只能放弃本轮截图");
    }

    @Test
    void undeployedOrMissingDeployKeyIsNotReachable() {
        App undeployed = deployedApp(3, "key-1");
        undeployed.setDeploymentStatus(AppDeploymentStatusEnum.UNDEPLOYED.getValue());
        assertFalse(ScreenshotServiceImpl.isDeployedToVersion(undeployed, 3));

        App noKey = deployedApp(3, null);
        assertFalse(ScreenshotServiceImpl.isDeployedToVersion(noKey, 3));
    }

    private App deployedApp(Integer deployedVersion, String deployKey) {
        App app = new App();
        app.setDeploymentStatus(AppDeploymentStatusEnum.DEPLOYED.getValue());
        app.setDeployedVersion(deployedVersion);
        app.setDeployKey(deployKey);
        return app;
    }
}
