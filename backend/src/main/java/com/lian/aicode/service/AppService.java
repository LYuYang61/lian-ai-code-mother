package com.lian.aicode.service;

import com.lian.aicode.model.dto.app.AppAddRequest;
import com.lian.aicode.model.dto.app.AppAdminUpdateRequest;
import com.lian.aicode.model.dto.app.AppFeaturedRequest;
import com.lian.aicode.model.dto.app.AppQueryRequest;
import com.lian.aicode.model.dto.app.AppUpdateRequest;
import com.lian.aicode.model.entity.App;
import com.lian.aicode.model.entity.UserAccount;
import com.lian.aicode.model.vo.AppVersionDiffVO;
import com.lian.aicode.model.vo.AppVersionVO;
import com.lian.aicode.model.vo.AppVO;
import com.lian.aicode.model.vo.ChatHistoryVO;
import com.lian.aicode.model.vo.PageResult;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;

/** 应用生命周期、AI 生成、版本和部署服务。 */
public interface AppService {

    Long createApp(AppAddRequest request, UserAccount loginUser);

    AppVO getAppVO(Long appId, UserAccount loginUser);

    PageResult<AppVO> listMyApps(AppQueryRequest request, UserAccount loginUser);

    PageResult<AppVO> listFeaturedApps(AppQueryRequest request);

    PageResult<AppVO> listAdminApps(AppQueryRequest request);

    boolean updateApp(AppUpdateRequest request, UserAccount loginUser);

    boolean adminUpdateApp(AppAdminUpdateRequest request);

    boolean deleteApp(Long appId, UserAccount loginUser);

    Flux<String> chatToGenCode(Long appId, String message, UserAccount loginUser);

    boolean stopGeneration(Long appId, UserAccount loginUser);

    String deployApp(Long appId, UserAccount loginUser);

    boolean disableDeployment(Long appId, UserAccount loginUser);

    String enableDeployment(Long appId, UserAccount loginUser);

    List<AppVersionVO> listVersions(Long appId, UserAccount loginUser);

    boolean rollback(Long appId, Integer versionNo, UserAccount loginUser);

    AppVersionDiffVO diff(Long appId, Integer fromVersion, Integer toVersion, UserAccount loginUser);

    List<ChatHistoryVO> listChatHistory(Long appId, UserAccount loginUser);

    boolean applyFeatured(AppFeaturedRequest request, UserAccount loginUser);

    App findByDeployKey(String deployKey);

    Path getPreviewPath(Long appId, Integer versionNo, UserAccount loginUser);

    Path getDeployPath(String deployKey);

    Path getDownloadPath(Long appId, UserAccount loginUser);
}
