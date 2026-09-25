package com.lian.aicode.service.impl;

import com.lian.aicode.mapper.AppVersionMapper;
import com.lian.aicode.mapper.ChatHistoryMapper;
import com.lian.aicode.model.entity.AppVersion;
import com.lian.aicode.model.entity.ChatHistory;
import com.lian.aicode.model.enums.AppVersionStatusEnum;
import com.lian.aicode.model.enums.ChatHistoryMessageTypeEnum;
import com.lian.aicode.service.ChatHistoryService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆恢复的"幽灵指令"过滤测试。
 *
 * <p>2026-09-25 真机事故：应用 v12 轮次被取消后，其用户消息「新增一个带图表的数据看板页面」
 * 留在 chat_history；下一次生成（v13）恢复对话记忆时把这条没有 AI 回应的指令一并装入上下文，
 * 模型把它当成主要任务，做了看板页而忽略真实需求（标题颜色 + 页脚版权行）。文件系统对取消
 * 轮次的回退是正确的（v13 从 v11 继承），对话记忆必须执行同样的回退语义：只有到达 ready 的
 * 版本，其消息才构成有效上下文。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class ChatHistoryMemoryGhostFilterTest {

    @Autowired
    private ChatHistoryService chatHistoryService;

    @Autowired
    private ChatHistoryMapper chatHistoryMapper;

    @Autowired
    private AppVersionMapper appVersionMapper;

    @Test
    void cancelledVersionUserMessageIsExcludedFromMemory() {
        long appId = 990_100L;
        seedVersion(appId, 11, AppVersionStatusEnum.READY);
        seedVersion(appId, 12, AppVersionStatusEnum.CANCELLED);
        seedMessage(appId, ChatHistoryMessageTypeEnum.USER, "在导航栏加一个品牌 Logo", 11);
        seedMessage(appId, ChatHistoryMessageTypeEnum.AI, "已在导航栏加入品牌 Logo", 11);
        seedMessage(appId, ChatHistoryMessageTypeEnum.USER, "新增一个带图表的数据看板页面", 12);
        seedMessage(appId, ChatHistoryMessageTypeEnum.ERROR, "本次生成已取消", 12);
        seedMessage(appId, ChatHistoryMessageTypeEnum.USER, "把页面主标题改成深蓝色", 13);

        List<ChatMessage> messages = chatHistoryService.loadMemoryMessages("app:" + appId, 20);

        String joined = joinText(messages);
        assertTrue(joined.contains("在导航栏加一个品牌 Logo"), "ready 版本的消息必须保留");
        assertTrue(joined.contains("已在导航栏加入品牌 Logo"), "ready 版本的 AI 消息必须保留");
        assertFalse(joined.contains("数据看板"), "取消版本的用户消息（幽灵指令）必须被排除");
        // loadMemoryMessages 额外排除最新一条用户消息（它由 LangChain4j 自动加入一次）
        assertFalse(joined.contains("主标题改成深蓝色"), "当前轮次的用户消息不应重复进入记忆");
    }

    @Test
    void failedVersionMessagesAreExcludedButLegacyNullVersionMessagesRemain() {
        long appId = 990_200L;
        seedVersion(appId, 2, AppVersionStatusEnum.FAILED);
        seedMessage(appId, ChatHistoryMessageTypeEnum.USER, "新增轮播图横幅", 2);
        // 早期版本功能上线前的历史消息没有版本号，不应因为过滤条件而丢失
        seedMessage(appId, ChatHistoryMessageTypeEnum.USER, "做一个产品发布官网", null);
        seedMessage(appId, ChatHistoryMessageTypeEnum.USER, "把页面主标题改成深蓝色", 3);

        List<ChatMessage> messages = chatHistoryService.loadMemoryMessages("app:" + appId, 20);

        String joined = joinText(messages);
        assertFalse(joined.contains("轮播图"), "失败版本的消息必须被排除");
        assertTrue(joined.contains("做一个产品发布官网"), "version_no 为空的早期消息必须保留");
    }

    private void seedVersion(long appId, int versionNo, AppVersionStatusEnum status) {
        LocalDateTime now = LocalDateTime.now();
        AppVersion version = AppVersion.builder()
                .appId(appId).versionNo(versionNo).status(status.getValue())
                .codeGenType("vue_project").relativePath("app/" + appId + "/v" + versionNo)
                .prompt("seed").createdBy(459262716257624064L)
                .createTime(now).updateTime(now).isDelete(0).build();
        appVersionMapper.insert(version);
    }

    private void seedMessage(long appId, ChatHistoryMessageTypeEnum type, String message, Integer versionNo) {
        LocalDateTime now = LocalDateTime.now();
        ChatHistory history = ChatHistory.builder()
                .appId(appId).userId(459262716257624064L).message(message).messageType(type.getValue())
                .parentId(null).versionNo(versionNo).fileList(null)
                .createTime(now).updateTime(now).isDelete(0).build();
        chatHistoryMapper.insert(history);
    }

    private String joinText(List<ChatMessage> messages) {
        StringBuilder builder = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message instanceof UserMessage userMessage) {
                builder.append(userMessage.singleText()).append('\n');
            } else if (message instanceof dev.langchain4j.data.message.AiMessage aiMessage
                    && aiMessage.text() != null) {
                builder.append(aiMessage.text()).append('\n');
            }
        }
        return builder.toString();
    }
}
