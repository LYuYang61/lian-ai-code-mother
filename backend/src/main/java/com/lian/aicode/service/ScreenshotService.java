package com.lian.aicode.service;

/** 应用网页封面截图、补偿重试和对象清理服务。 */
public interface ScreenshotService {

    /** 在事务提交后异步提交一项截图任务；任务满或外部能力未配置时不影响主业务。 */
    void submit(Long appId, Integer versionNo, String webUrl, String trigger, String actorAccount);

    /** 仅在当前应用没有封面且版本仍是当前版本时提交截图任务。 */
    void submitIfMissing(Long appId, Integer versionNo, String webUrl, String trigger, String actorAccount);

    /** 删除当前阿里云 OSS 公共域名下的历史封面；外部 URL 不会被盲目删除。 */
    void deleteCover(String coverUrl, Long appId, String actorAccount);
}
