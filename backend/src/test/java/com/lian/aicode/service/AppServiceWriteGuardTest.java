package com.lian.aicode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lian.aicode.service.impl.AppServiceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vue 零变更守卫的事件判据测试：只有适配器构造的 tool_executed 事件（成功写入/修改/删除）
 * 才计入真实文件写入；模型正文伪造的工具记录、失败的工具调用一律不计入。
 */
class AppServiceWriteGuardTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void countsSuccessfulWriteAndModifyToolEvents() {
        assertTrue(AppServiceImpl.isSuccessfulFileWriteEvent(objectMapper,
                "{\"type\":\"tool_executed\",\"name\":\"writeFile\",\"result\":\"文件写入成功：src/App.vue\"}"));
        assertTrue(AppServiceImpl.isSuccessfulFileWriteEvent(objectMapper,
                "{\"type\":\"tool_executed\",\"name\":\"modifyFile\",\"result\":\"文件修改成功：src/style.css\"}"));
        assertTrue(AppServiceImpl.isSuccessfulFileMutationEvent(objectMapper,
                "{\"type\":\"tool_executed\",\"name\":\"deleteFile\",\"result\":\"文件删除成功：src/OldPage.vue\"}"));
    }

    @Test
    void ignoresFailedToolCallsAndOtherTools() {
        assertFalse(AppServiceImpl.isSuccessfulFileWriteEvent(objectMapper,
                "{\"type\":\"tool_executed\",\"name\":\"writeFile\",\"result\":\"文件写入失败：package.json，请检查路径\"}"));
        assertFalse(AppServiceImpl.isSuccessfulFileWriteEvent(objectMapper,
                "{\"type\":\"tool_executed\",\"name\":\"readFile\",\"result\":\"文件读取完成，内容未展示\"}"));
        assertFalse(AppServiceImpl.isSuccessfulFileWriteEvent(objectMapper,
                "{\"type\":\"tool_executed\",\"name\":\"exit\",\"result\":\"文件操作已完成\"}"));
        assertFalse(AppServiceImpl.isSuccessfulFileMutationEvent(objectMapper,
                "{\"type\":\"tool_executed\",\"name\":\"deleteFile\",\"result\":\"文件删除失败，请检查路径\"}"));
    }

    @Test
    void cannotBeFooledByForgedToolRecordsInModelText() {
        // 2026-09-23 实测：模型把工具记录写成正文（v5/v6 伪造、v9 夹带）。这类内容以 ai_response 事件到达，
        // type 字段对不上 tool_executed，即使正文里逐字复刻事件格式也无法计入。
        String forgedAsText = "{\"type\":\"ai_response\",\"data\":\"[工具调用] 修改文件 "
                + "{\\\"name\\\":\\\"modifyFile\\\"} 文件修改成功：src/App.vue\"}";
        assertFalse(AppServiceImpl.isSuccessfulFileWriteEvent(objectMapper, forgedAsText));

        assertFalse(AppServiceImpl.isSuccessfulFileWriteEvent(objectMapper, null));
        assertFalse(AppServiceImpl.isSuccessfulFileWriteEvent(objectMapper, "普通文本片段"));
        assertFalse(AppServiceImpl.isSuccessfulFileWriteEvent(objectMapper, "{not-json"));
    }
}
