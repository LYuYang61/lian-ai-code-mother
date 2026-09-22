package com.lian.aicode.ai;

import com.lian.aicode.ai.model.HtmlCodeResult;
import com.lian.aicode.ai.model.MultiFileCodeResult;
import com.lian.aicode.ai.model.AppNameResult;
import com.lian.aicode.ai.model.ConversationSummaryResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

/**
 * LangChain4j AI Service 声明。
 *
 * <p>接口只描述消息流转和返回协议，模型客户端由 Spring Boot starter 创建，避免业务代码
 * 直接依赖某个厂商 SDK。阻塞方法用于结构化结果，流式方法用于实时输出；两者不能混用结构化返回。</p>
 */
public interface AiCodeGeneratorService {

    /** 生成单 HTML 文件的结构化结果。 */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    HtmlCodeResult generateHtmlCode(@UserMessage String userMessage);

    /** 生成 HTML、CSS、JavaScript 三文件的结构化结果。 */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    MultiFileCodeResult generateMultiFileCode(@UserMessage String userMessage);

    /** 根据需求生成简短应用名；失败时业务层会退回本地截断策略。 */
    @SystemMessage(fromResource = "prompt/app-name-system-prompt.txt")
    AppNameResult generateAppName(@UserMessage String userMessage);

    /** 压缩历史对话，摘要只用于后续模型上下文，不替代数据库原始记录。 */
    @SystemMessage(fromResource = "prompt/chat-summary-system-prompt.txt")
    ConversationSummaryResult summarizeConversation(@UserMessage String conversation);

    /**
     * 生成单 HTML 文件的文本流。
     * LangChain4j 的 Reactor 扩展会把底层 TokenStream 转为 Flux<String>。
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    Flux<String> generateHtmlCodeStream(@UserMessage String userMessage);

    /** 生成三文件网页的文本流。 */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    Flux<String> generateMultiFileCodeStream(@UserMessage String userMessage);
}
