package com.lian.aicode.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用 H2 验证注册、Session 登录、应用 CRUD 和 Long ID 字符串序列化。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppUserFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void registerLoginAndCreateApp() throws Exception {
        mockMvc.perform(post("/user/register")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"userAccount":"flow_user","userPassword":"password-123","userName":"流程用户"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        var loginResult = mockMvc.perform(post("/user/login")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"userAccount":"flow_user","userPassword":"password-123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isString())
                .andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        String appResponse = mockMvc.perform(post("/app/add")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"initPrompt":"一个带待办清单的网页","codeGenType":"html","visibility":"private","tags":"效率,工具"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isString())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(appResponse);
        String appId = body.path("data").asText();

        mockMvc.perform(get("/app/get/vo").param("id", appId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(appId))
                .andExpect(jsonPath("$.data.tags").value("效率,工具"));

        mockMvc.perform(post("/app/my/list/page/vo")
                        .session(session)
                        .contentType(APPLICATION_JSON)
                        .content("{\"pageNum\":1,\"pageSize\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }
}
