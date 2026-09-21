package com.lian.aicode.core;

import com.lian.aicode.ai.AiCodeGeneratorService;
import com.lian.aicode.ai.AiCodeGeneratorServiceFactory;
import com.lian.aicode.core.parser.CodeParserExecutor;
import com.lian.aicode.core.saver.CodeFileSaverExecutor;
import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import com.lian.aicode.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * AI 代码生成门面：统一编排模型调用、流式收集、解析和固定文件落盘。
 *
 * <p>应用生成会按 appId 选择隔离的 AI Service；没有配置 API Key 的开发环境仍可启动，
 * 直到真正调用 AI 时才返回明确的配置错误。</p>
 */
@Slf4j
@Service
public class AiCodeGeneratorFacade {

    private final Supplier<AiCodeGeneratorService> defaultAiServiceSupplier;
    private final ObjectProvider<AiCodeGeneratorServiceFactory> serviceFactoryProvider;
    private final CodeParserExecutor codeParserExecutor;
    private final CodeFileSaverExecutor codeFileSaverExecutor;

    /** Spring 使用该构造器；没有 API Key 时 provider 为空，但基础应用仍可启动。 */
    @Autowired
    public AiCodeGeneratorFacade(ObjectProvider<AiCodeGeneratorService> aiServiceProvider,
                                 ObjectProvider<AiCodeGeneratorServiceFactory> serviceFactoryProvider,
                                 CodeParserExecutor codeParserExecutor,
                                 CodeFileSaverExecutor codeFileSaverExecutor) {
        this.defaultAiServiceSupplier = () -> aiServiceProvider.getIfAvailable(() -> {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "AI 模型未配置，请设置 DEEPSEEK_API_KEY 并启用 local profile");
        });
        this.serviceFactoryProvider = serviceFactoryProvider;
        this.codeParserExecutor = codeParserExecutor;
        this.codeFileSaverExecutor = codeFileSaverExecutor;
    }

    /** 供不依赖 Spring 上下文的单元测试使用。 */
    public AiCodeGeneratorFacade(AiCodeGeneratorService aiCodeGeneratorService,
                                 CodeParserExecutor codeParserExecutor,
                                 CodeFileSaverExecutor codeFileSaverExecutor) {
        this.defaultAiServiceSupplier = () -> Objects.requireNonNull(aiCodeGeneratorService);
        this.serviceFactoryProvider = null;
        this.codeParserExecutor = codeParserExecutor;
        this.codeFileSaverExecutor = codeFileSaverExecutor;
    }

    /** 根据类型同步生成并保存代码。 */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenType) {
        return generateAndSaveCode(null, userMessage, codeGenType, null);
    }

    /** 为应用版本生成并保存到指定目录。 */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenType, Path outputDirectory) {
        return generateAndSaveCode(null, userMessage, codeGenType, outputDirectory);
    }

    /** 为指定应用选择隔离的 AI Service，同步生成并保存代码。 */
    public File generateAndSaveCode(Long appId, String userMessage, CodeGenTypeEnum codeGenType,
                                    Path outputDirectory) {
        validateRequest(userMessage, codeGenType);
        AiCodeGeneratorService service = getService(appId);
        Object result = switch (codeGenType) {
            case HTML -> service.generateHtmlCode(userMessage);
            case MULTI_FILE -> service.generateMultiFileCode(userMessage);
        };
        return outputDirectory == null
                ? codeFileSaverExecutor.executeSaver(result, codeGenType)
                : codeFileSaverExecutor.executeSaver(result, codeGenType, outputDirectory);
    }

    /** 根据类型生成并保存代码，同时把模型文本片段实时返回给调用方。 */
    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenType) {
        return generateAndSaveCodeStream(null, userMessage, codeGenType, null);
    }

    /** 为应用版本流式生成；outputDirectory 为空时保持基础阶段的随机目录行为。 */
    public Flux<String> generateAndSaveCodeStream(String userMessage,
                                                   CodeGenTypeEnum codeGenType,
                                                   Path outputDirectory) {
        return generateAndSaveCodeStream(null, userMessage, codeGenType, outputDirectory);
    }

    /** 为指定应用使用隔离的 ChatMemory 流式生成。 */
    public Flux<String> generateAndSaveCodeStream(Long appId,
                                                   String userMessage,
                                                   CodeGenTypeEnum codeGenType,
                                                   Path outputDirectory) {
        return generateAndSaveCodeStream(appId, userMessage, codeGenType, outputDirectory, null);
    }

    /** 为指定应用流式生成，并排除本轮已经落库的用户历史记录。 */
    public Flux<String> generateAndSaveCodeStream(Long appId,
                                                   String userMessage,
                                                   CodeGenTypeEnum codeGenType,
                                                   Path outputDirectory,
                                                   Long excludedMessageId) {
        validateRequest(userMessage, codeGenType);
        return Flux.defer(() -> {
            AiCodeGeneratorService service = getService(appId, excludedMessageId);
            Flux<String> codeStream = switch (codeGenType) {
                case HTML -> service.generateHtmlCodeStream(userMessage);
                case MULTI_FILE -> service.generateMultiFileCodeStream(userMessage);
            };
            if (codeStream == null) {
                return Flux.error(new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 未返回代码流"));
            }
            return processCodeStream(codeStream, codeGenType, outputDirectory);
        });
    }

    /** 应用删除或明确清空上下文时调用。 */
    public void evictAppMemory(Long appId) {
        if (serviceFactoryProvider == null) {
            return;
        }
        AiCodeGeneratorServiceFactory factory = serviceFactoryProvider.getIfAvailable();
        if (factory != null) {
            try {
                factory.evictAppService(appId);
            } catch (RuntimeException exception) {
                log.warn("清理应用 Redis AI 记忆失败，不影响应用删除：appId={}", appId, exception);
            }
        }
    }

    private AiCodeGeneratorService getService(Long appId) {
        return getService(appId, null);
    }

    private AiCodeGeneratorService getService(Long appId, Long excludedMessageId) {
        if (appId == null || appId <= 0 || serviceFactoryProvider == null) {
            return defaultAiServiceSupplier.get();
        }
        AiCodeGeneratorServiceFactory factory = serviceFactoryProvider.getIfAvailable();
        return factory == null ? defaultAiServiceSupplier.get() : factory.getForApp(appId, excludedMessageId);
    }

    /**
     * 收集模型流式输出，最终解析并保存代码。
     * 保存动作放在 concatWith 中，只有上游完整结束后才会执行；保存失败会传播为 Flux 错误。
     */
    private Flux<String> processCodeStream(Flux<String> codeStream,
                                           CodeGenTypeEnum codeGenType,
                                           Path outputDirectory) {
        StringBuilder codeBuilder = new StringBuilder();
        return codeStream
                .doOnNext(chunk -> {
                    if (chunk != null) {
                        codeBuilder.append(chunk);
                    }
                })
                .concatWith(Flux.defer(() -> {
                    String completeCode = codeBuilder.toString();
                    Object parsedResult = codeParserExecutor.executeParser(completeCode, codeGenType);
                    File savedDirectory = outputDirectory == null
                            ? codeFileSaverExecutor.executeSaver(parsedResult, codeGenType)
                            : codeFileSaverExecutor.executeSaver(parsedResult, codeGenType, outputDirectory);
                    log.info("代码保存成功：type={}, directory={}",
                            codeGenType.getValue(), savedDirectory.getAbsolutePath());
                    return Flux.empty();
                }))
                .doOnError(error -> log.warn("代码生成或保存失败：type={}, errorType={}",
                        codeGenType.getValue(), error == null ? "未知异常" : error.getClass().getSimpleName()));
    }

    private void validateRequest(String userMessage, CodeGenTypeEnum codeGenType) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户需求描述不能为空");
        }
        if (codeGenType == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码生成类型不能为空");
        }
    }
}
