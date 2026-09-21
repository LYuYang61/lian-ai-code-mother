package com.lian.aicode.service;

import com.lian.aicode.model.dto.chathistory.ChatHistoryQueryRequest;
import com.lian.aicode.model.entity.ChatHistory;
import com.lian.aicode.model.entity.UserAccount;
import com.lian.aicode.model.enums.ChatHistoryMessageTypeEnum;
import com.lian.aicode.model.vo.ChatHistoryStatsVO;
import com.lian.aicode.model.vo.ChatHistoryVO;
import com.lian.aicode.model.vo.ChatSummaryVO;
import com.lian.aicode.model.vo.CursorPageResult;
import com.lian.aicode.model.vo.PageResult;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.data.message.ChatMessage;

import java.time.LocalDateTime;
import java.util.List;

/** 对话原始历史、游标查询、导出和摘要服务。 */
public interface ChatHistoryService {

    ChatHistory addMessage(Long appId, Long userId, String message, ChatHistoryMessageTypeEnum type,
                           Long parentId, Integer versionNo, String fileList);

    boolean deleteByAppId(Long appId);

    CursorPageResult<ChatHistoryVO> listAppHistory(Long appId, int pageSize, LocalDateTime lastCreateTime,
                                                   Long lastId, UserAccount loginUser);

    List<ChatHistoryVO> listRecent(Long appId, UserAccount loginUser, int limit);

    PageResult<ChatHistoryVO> listAdmin(ChatHistoryQueryRequest request, UserAccount loginUser);

    boolean deleteMessage(Long id, UserAccount loginUser);

    int loadChatHistoryToMemory(Long appId, ChatMemory chatMemory, int maxCount, Long excludedMessageId);

    /** Redis 记忆缺失时，从数据库构造可供 LangChain4j 使用的消息窗口。 */
    List<ChatMessage> loadMemoryMessages(String memoryId, int maxCount);

    ChatHistoryStatsVO stats(Long appId, UserAccount loginUser);

    byte[] exportMarkdown(Long appId, UserAccount loginUser);

    ChatSummaryVO summarize(Long appId, UserAccount loginUser);

    /**
     * 在对话数量达到阈值后异步触发摘要；该方法不能阻塞生成请求，也不改变原始历史记录。
     */
    void triggerSummaryIfNeeded(Long appId);
}
