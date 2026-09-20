package com.lian.aicode.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lian.aicode.ai.AiCodeGeneratorService;
import com.lian.aicode.ai.model.AppNameResult;
import com.lian.aicode.mapper.UserAccountMapper;
import com.lian.aicode.model.entity.UserAccount;
import com.lian.aicode.model.enums.UserRoleEnum;
import com.lian.aicode.service.AppStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import reactor.core.publisher.Flux;

/**
 * 覆盖第四期核心生命周期；AI 服务使用桩实现，不访问真实 DeepSeek，也不需要 API Key。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AppLifecycleFlowTest.AiStubConfiguration.class)
class AppLifecycleFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AiCodeGeneratorService aiCodeGeneratorService;

    @Autowired
    private UserAccountMapper userAccountMapper;

    @Autowired
    private AppStorageService storageService;

    @Test
    void completesGenerateVersionPreviewDeployRollbackFeatureAndCleanupFlow() throws Exception {
        when(aiCodeGeneratorService.generateAppName(anyString())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "AI 应用命名不应在数据库事务中执行");
            return appName("任务工作台");
        });
        when(aiCodeGeneratorService.generateMultiFileCodeStream(anyString()))
                .thenReturn(Flux.just(
                        "```html\n<!doctype html><html><body>version-one</body></html>\n```\n",
                        "```css\nbody { color: navy; }\n```\n",
                        "```javascript\nconsole.log('one');\n```"))
                .thenReturn(Flux.just(
                        "```html\n<!doctype html><html><body>version-two</body></html>\n```\n",
                        "```css\nbody { color: green; }\n```\n",
                        "```javascript\nconsole.log('two');\n```"))
                .thenReturn(Flux.never());

        MockHttpSession ownerSession = registerAndLogin("owner_" + shortId(), "流程用户");
        String appId = createPublicApp(ownerSession);

        String firstStream = generate(ownerSession, appId, "生成第一版任务页面");
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertTrue(firstStream.contains("event:done")),
                () -> assertTrue(firstStream.contains("version-one")));
        mockMvc.perform(get("/app/get/vo").param("id", appId).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appName").value("任务工作台"))
                .andExpect(jsonPath("$.data.currentVersion").value(1))
                .andExpect(jsonPath("$.data.generationStatus").value("ready"));
        mockMvc.perform(get("/preview/" + appId + "/1/")
                        .accept(MediaType.TEXT_HTML_VALUE))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("version-one")));

        String secondStream = generate(ownerSession, appId, "把页面改成第二版");
        assertTrue(secondStream.contains("event:done"));
        mockMvc.perform(get("/app/version/diff")
                        .session(ownerSession)
                .param("appId", appId).param("fromVersion", "1").param("toVersion", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files['index.html']", containsString("- <!doctype html>")))
                .andExpect(jsonPath("$.data.files['index.html']", containsString("+ <!doctype html>")));

        // 公开访客只可看到当前版本；历史代码、差异和对话记录属于创建者/管理员数据。
        mockMvc.perform(get("/app/version/list").param("appId", appId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/app/version/diff")
                        .param("appId", appId).param("fromVersion", "1").param("toVersion", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40101));
        mockMvc.perform(get("/app/chat/history").param("appId", appId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40101));
        mockMvc.perform(get("/preview/" + appId + "/1/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40101));

        String deployResponse = mockMvc.perform(post("/app/deploy")
                        .session(ownerSession).contentType(APPLICATION_JSON)
                        .content("{\"appId\":\"" + appId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isString())
                .andReturn().getResponse().getContentAsString();
        String deployUrl = objectMapper.readTree(deployResponse).path("data").asText();
        String deployKey = deployUrl.substring(deployUrl.indexOf("/site/") + "/site/".length())
                .replaceAll("/$", "");
        mockMvc.perform(get("/site/" + deployKey + "/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("version-two")));

        mockMvc.perform(post("/app/deploy/disable")
                        .session(ownerSession).contentType(APPLICATION_JSON)
                        .content("{\"appId\":\"" + appId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
        mockMvc.perform(get("/site/" + deployKey + "/")).andExpect(status().isNotFound());

        mockMvc.perform(post("/app/deploy/enable")
                        .session(ownerSession).contentType(APPLICATION_JSON)
                        .content("{\"appId\":\"" + appId + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/app/version/rollback")
                        .session(ownerSession).contentType(APPLICATION_JSON)
                        .content("{\"appId\":\"" + appId + "\",\"versionNo\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
        mockMvc.perform(get("/site/" + deployKey + "/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("version-one")));

        MvcResult runningRequest = mockMvc.perform(get("/app/chat/gen/code")
                        .session(ownerSession).accept(MediaType.TEXT_EVENT_STREAM)
                        .param("appId", appId).param("message", "中断这次长时间生成"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(post("/app/chat/stop").session(ownerSession)
                        .contentType(APPLICATION_JSON)
                        .content("{\"id\":\"" + appId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
        String cancelledStream = mockMvc.perform(asyncDispatch(runningRequest))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(cancelledStream.contains("event:cancelled"));
        assertFalse(cancelledStream.contains("event:done"));
        mockMvc.perform(get("/app/get/vo").param("id", appId).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.generationStatus").value("cancelled"))
                .andExpect(jsonPath("$.data.currentVersion").value(1));

        mockMvc.perform(post("/app/featured/apply")
                        .session(ownerSession).contentType(APPLICATION_JSON)
                        .content("{\"appId\":\"" + appId + "\",\"reason\":\"展示第一版\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        UserAccount admin = promoteToAdmin("admin_" + shortId());
        MockHttpSession adminSession = login(admin.getUserAccount(), "password-123");
        mockMvc.perform(post("/app/update")
                        .session(adminSession).contentType(APPLICATION_JSON)
                        .content("{\"id\":\"" + appId + "\",\"appName\":\"越权修改\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40101));
        mockMvc.perform(post("/app/admin/update")
                        .session(adminSession).contentType(APPLICATION_JSON)
                        .content("{\"id\":\"" + appId
                                + "\",\"featuredStatus\":\"approved\",\"priority\":999}"
                        ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
        mockMvc.perform(post("/app/good/list/page/vo")
                        .contentType(APPLICATION_JSON)
                        .content("{\"pageNum\":1,\"pageSize\":20,\"tag\":\"工具\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(post("/app/delete")
                        .session(ownerSession).contentType(APPLICATION_JSON)
                        .content("{\"id\":\"" + appId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
        long numericAppId = Long.parseLong(appId);
        assertFalse(Files.exists(storageService.versionDirectory(numericAppId, 1)));
        assertFalse(Files.exists(storageService.deployDirectory(deployKey)));
        mockMvc.perform(get("/app/get/vo").param("id", appId).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40400));
    }

    private String createPublicApp(MockHttpSession session) throws Exception {
        String response = mockMvc.perform(post("/app/add").session(session)
                        .contentType(APPLICATION_JSON)
                        .content("{\"initPrompt\":\"一个带工具标签的任务网页\","
                                + "\"codeGenType\":\"multi_file\",\"visibility\":\"public\","
                                + "\"category\":\"效率\",\"tags\":\"效率,工具\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isString())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").asText();
    }

    private String generate(MockHttpSession session, String appId, String prompt) throws Exception {
        MvcResult initial = mockMvc.perform(get("/app/chat/gen/code")
                        .session(session).accept(MediaType.TEXT_EVENT_STREAM)
                        .param("appId", appId).param("message", prompt))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andReturn();
        return completed.getResponse().getContentAsString();
    }

    private MockHttpSession registerAndLogin(String account, String userName) throws Exception {
        mockMvc.perform(post("/user/register").contentType(APPLICATION_JSON)
                        .content("{\"userAccount\":\"" + account
                                + "\",\"userPassword\":\"password-123\",\"userName\":\"" + userName + "\"}"))
                .andExpect(status().isOk());
        return login(account, "password-123");
    }

    private MockHttpSession login(String account, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/user/login").contentType(APPLICATION_JSON)
                        .content("{\"userAccount\":\"" + account
                                + "\",\"userPassword\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private UserAccount promoteToAdmin(String account) throws Exception {
        registerAndLogin(account, "管理员");
        UserAccount admin = userAccountMapper.selectOneByQuery(
                com.mybatisflex.core.query.QueryWrapper.create().eq("user_account", account));
        admin.setUserRole(UserRoleEnum.ADMIN.getValue());
        userAccountMapper.update(admin);
        return admin;
    }

    private AppNameResult appName(String value) {
        AppNameResult result = new AppNameResult();
        result.setAppName(value);
        return result;
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AiStubConfiguration {

        @Bean
        AiCodeGeneratorService aiCodeGeneratorService() {
            return org.mockito.Mockito.mock(AiCodeGeneratorService.class);
        }
    }
}
