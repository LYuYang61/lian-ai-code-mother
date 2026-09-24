package com.lian.aicode.core.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lian.aicode.ai.model.message.StreamMessage;
import com.lian.aicode.ai.model.message.StreamMessageTypeEnum;
import com.lian.aicode.ai.tools.ProjectToolBundle;
import com.lian.aicode.ai.tools.ProjectToolContext;
import com.lian.aicode.core.builder.VueProjectBuilder;
import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.util.Map;

/**
 * 将 LangChain4j 官方 TokenStream 适配为平台自己的 JSON 事件流。
 *
 * <p>只在工具真正准备执行时发送一次完整工具请求，避免把不完整的 JSON 参数片段直接交给
 * 前端解析；模型思考内容可以实时展示，但不会写入对话历史，防止把内部推理当成可复用事实。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenStreamAdapter {

    private static final int MAX_DISPLAY_RESULT_LENGTH = 800;
    private static final int MAX_STREAM_CHUNK_LENGTH = 16_000;

    private final ObjectMapper objectMapper;
    private final VueProjectBuilder projectBuilder;

    public Flux<String> adapt(TokenStream tokenStream, ProjectToolBundle toolBundle,
                              ProjectToolContext context) {
        return Flux.create(sink -> {
            sink.onCancel(context::cancel);
            try {
                tokenStream
                        .onPartialResponse(text -> emit(sink, StreamMessage.builder()
                                .type(StreamMessageTypeEnum.AI_RESPONSE.getValue())
                                .data(safeText(text, MAX_STREAM_CHUNK_LENGTH))
                                .build()))
                        .onPartialThinking(thinking -> emit(sink, thinkingMessage(thinking)))
                        .beforeToolExecution(before -> emit(sink, toolRequestMessage(before, toolBundle)))
                        .onToolExecuted(execution -> emit(sink, toolExecutedMessage(execution, toolBundle, context)))
                        .onCompleteResponse(response -> completeBuild(sink, context))
                        .onError(error -> {
                            log.warn("AI TokenStream 失败：actor={}, appId={}, version={}, reason={}",
                                    context.getActorAccount(), context.getAppId(), context.getVersionNo(),
                                    describe(error));
                            if (!sink.isCancelled()) {
                                sink.error(error == null ? new IllegalStateException("AI 流异常") : error);
                            }
                        })
                        .start();
            } catch (RuntimeException exception) {
                log.warn("启动 AI TokenStream 失败：actor={}, appId={}, version={}, reason={}",
                        context.getActorAccount(), context.getAppId(), context.getVersionNo(),
                        describe(exception));
                sink.error(exception);
            }
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    /** 类名不足以定位问题：轮次上限、鉴权失败、网络中断都需要 message 才能区分。 */
    private static String describe(Throwable error) {
        if (error == null) {
            return "未知异常";
        }
        String detail = error.getMessage() == null ? "" : ": " + error.getMessage();
        return error.getClass().getSimpleName() + detail.substring(0, Math.min(detail.length(), 200));
    }

    private StreamMessage thinkingMessage(PartialThinking thinking) {
        return StreamMessage.builder()
                .type(StreamMessageTypeEnum.THINKING.getValue())
                .data(safeText(thinking == null ? null : thinking.text(), MAX_STREAM_CHUNK_LENGTH))
                .build();
    }

    private StreamMessage toolRequestMessage(BeforeToolExecution before, ProjectToolBundle toolBundle) {
        ToolExecutionRequest request = before.request();
        return StreamMessage.builder()
                .type(StreamMessageTypeEnum.TOOL_REQUEST.getValue())
                .id(request.id())
                .name(request.name())
                .displayName(toolBundle.displayName(request.name()))
                .arguments(summarizeArguments(request.arguments()))
                .build();
    }

    private StreamMessage toolExecutedMessage(ToolExecution execution, ProjectToolBundle toolBundle,
                                              ProjectToolContext context) {
        ToolExecutionRequest request = execution.request();
        Duration duration = execution.duration();
        log.info("AI 工具执行完成：actor={}, appId={}, version={}, tool={}, result={}, durationMs={}",
                context.getActorAccount(), context.getAppId(), context.getVersionNo(),
                request.name(), execution.hasFailed() ? "失败" : "成功",
                duration == null ? null : duration.toMillis());
        return StreamMessage.builder()
                .type(StreamMessageTypeEnum.TOOL_EXECUTED.getValue())
                .id(request.id())
                .name(request.name())
                .displayName(toolBundle.displayName(request.name()))
                .arguments(summarizeArguments(request.arguments()))
                // readFile 的返回值是源代码，模型需要在内部继续推理，但不应通过 SSE 或历史记录回显。
                .result(displayResult(request, execution))
                .build();
    }

    private void completeBuild(FluxSink<String> sink, ProjectToolContext context) {
        if (context.isCancelled() || sink.isCancelled()) {
            return;
        }
        log.info("Vue 工程构建开始：actor={}, appId={}, version={}", context.getActorAccount(),
                context.getAppId(), context.getVersionNo());
        if (!projectBuilder.buildProject(context.getProjectRoot())) {
            log.warn("Vue 工程构建失败：actor={}, appId={}, version={}, result=失败",
                    context.getActorAccount(), context.getAppId(), context.getVersionNo());
            sink.error(new BusinessException(ErrorCode.OPERATION_ERROR, "Vue 工程构建失败，请检查生成文件和 Node.js 环境"));
            return;
        }
        log.info("Vue 工程构建完成：actor={}, appId={}, version={}, result=成功",
                context.getActorAccount(), context.getAppId(), context.getVersionNo());
        sink.complete();
    }

    private void emit(FluxSink<String> sink, StreamMessage message) {
        if (sink.isCancelled() || message == null) {
            return;
        }
        try {
            sink.next(objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException exception) {
            sink.error(new IllegalStateException("流式消息编码失败", exception));
        }
    }

    private String summarizeArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "{}";
        }
        try {
            JsonNode node = objectMapper.readTree(arguments);
            Map<String, String> summary = new java.util.LinkedHashMap<>();
            for (String key : new String[]{"relativeFilePath", "relativeDirPath"}) {
                JsonNode value = node.get(key);
                if (value != null && value.isTextual()) {
                    summary.put(key, value.asText().replace('\\', '/').substring(0,
                            Math.min(value.asText().length(), 160)));
                }
            }
            return objectMapper.writeValueAsString(summary);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String displayResult(ToolExecutionRequest request, ToolExecution execution) {
        if (request != null && "readFile".equals(request.name())) {
            // 源码只应留在模型上下文，不能因为源码中恰好出现“失败”等词就被回显到浏览器。
            return execution.hasFailed() ? "文件读取失败" : "文件读取完成，内容未展示";
        }
        return safeText(execution.result(), MAX_DISPLAY_RESULT_LENGTH);
    }

    private String safeText(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\u0000", "");
        return normalized.substring(0, Math.min(normalized.length(), maxLength));
    }
}
