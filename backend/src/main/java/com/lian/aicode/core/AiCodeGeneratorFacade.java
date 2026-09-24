package com.lian.aicode.core;

import com.lian.aicode.ai.AiCodeGeneratorService;
import com.lian.aicode.ai.AiCodeGeneratorServiceFactory;
import com.lian.aicode.ai.tools.ProjectToolBundle;
import com.lian.aicode.ai.tools.ProjectToolContext;
import com.lian.aicode.core.parser.CodeParserExecutor;
import com.lian.aicode.core.saver.CodeFileSaverExecutor;
import com.lian.aicode.core.stream.TokenStreamAdapter;
import com.lian.aicode.core.template.VueProjectTemplateService;
import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import com.lian.aicode.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final TokenStreamAdapter tokenStreamAdapter;
    private final VueProjectTemplateService templateService;

    private static final Pattern VERSION_DIRECTORY_PATTERN = Pattern.compile("(?:^|[/\\\\])v(\\d+)$");

    @Value("${app.vue-project.max-files:80}")
    private int vueMaxFiles;

    @Value("${app.vue-project.max-total-bytes:10485760}")
    private long vueMaxTotalBytes;

    @Value("${app.vue-project.max-file-size-bytes:2097152}")
    private int maxFileSizeBytes;

    /** Spring 使用该构造器；没有 API Key 时 provider 为空，但基础应用仍可启动。 */
    @Autowired
    public AiCodeGeneratorFacade(ObjectProvider<AiCodeGeneratorService> aiServiceProvider,
                                 ObjectProvider<AiCodeGeneratorServiceFactory> serviceFactoryProvider,
                                 CodeParserExecutor codeParserExecutor,
                                 CodeFileSaverExecutor codeFileSaverExecutor,
                                 TokenStreamAdapter tokenStreamAdapter,
                                 VueProjectTemplateService templateService) {
        this.defaultAiServiceSupplier = () -> aiServiceProvider.getIfAvailable(() -> {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "AI 模型未配置，请设置 DEEPSEEK_API_KEY 并启用 local profile");
        });
        this.serviceFactoryProvider = serviceFactoryProvider;
        this.codeParserExecutor = codeParserExecutor;
        this.codeFileSaverExecutor = codeFileSaverExecutor;
        this.tokenStreamAdapter = tokenStreamAdapter;
        this.templateService = templateService;
    }

    /** 供不依赖 Spring 上下文的单元测试使用。 */
    public AiCodeGeneratorFacade(AiCodeGeneratorService aiCodeGeneratorService,
                                 CodeParserExecutor codeParserExecutor,
                                 CodeFileSaverExecutor codeFileSaverExecutor) {
        this.defaultAiServiceSupplier = () -> Objects.requireNonNull(aiCodeGeneratorService);
        this.serviceFactoryProvider = null;
        this.codeParserExecutor = codeParserExecutor;
        this.codeFileSaverExecutor = codeFileSaverExecutor;
        this.tokenStreamAdapter = null;
        this.templateService = null;
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
        if (codeGenType == CodeGenTypeEnum.VUE_PROJECT) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "Vue 工程只支持流式工具调用生成");
        }
        AiCodeGeneratorService service = getService(appId);
        Object result = switch (codeGenType) {
            case HTML -> service.generateHtmlCode(userMessage);
            case MULTI_FILE -> service.generateMultiFileCode(userMessage);
            case VUE_PROJECT -> throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Vue 工程只支持流式工具调用生成");
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
        return generateAndSaveCodeStream(appId, userMessage, codeGenType, outputDirectory,
                excludedMessageId, null, "system");
    }

    /** 带版本和操作者上下文的流式生成入口。 */
    public Flux<String> generateAndSaveCodeStream(Long appId,
                                                   String userMessage,
                                                   CodeGenTypeEnum codeGenType,
                                                   Path outputDirectory,
                                                   Long excludedMessageId,
                                                   Integer versionNo,
                                                   String actorAccount) {
        return generateAndSaveCodeStream(appId, userMessage, codeGenType, outputDirectory,
                excludedMessageId, versionNo, actorAccount, false);
    }

    /**
     * 带创建/迭代语义的流式生成入口。Vue 工程会据此选择创建提示词或增量修改提示词；
     * HTML 和多文件模式仍由各自的全量结果解析器负责保存。
     */
    public Flux<String> generateAndSaveCodeStream(Long appId,
                                                   String userMessage,
                                                   CodeGenTypeEnum codeGenType,
                                                   Path outputDirectory,
                                                   Long excludedMessageId,
                                                   Integer versionNo,
                                                   String actorAccount,
                                                   boolean modification) {
        validateRequest(userMessage, codeGenType);
        return Flux.defer(() -> {
            long startedAt = System.nanoTime();
            log.info("AI 调用开始：appId={}, version={}, type={}, mode={}, actor={}",
                    appId, versionNo, codeGenType.getValue(), modification ? "修改" : "创建", actorAccount);
            try {
                if (codeGenType == CodeGenTypeEnum.VUE_PROJECT) {
                    return generateVueProjectStream(appId, userMessage, outputDirectory, excludedMessageId,
                            versionNo, actorAccount, modification, startedAt);
                }
                AiCodeGeneratorService service = getService(appId, excludedMessageId);
                Flux<String> codeStream = switch (codeGenType) {
                    case HTML -> service.generateHtmlCodeStream(userMessage);
                    case MULTI_FILE -> service.generateMultiFileCodeStream(userMessage);
                    case VUE_PROJECT -> Flux.error(new BusinessException(ErrorCode.OPERATION_ERROR,
                            "Vue 工程流未正确路由"));
                };
                if (codeStream == null) {
                    return Flux.error(new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 未返回代码流"));
                }
                return processCodeStream(codeStream, codeGenType, outputDirectory,
                        appId, versionNo, actorAccount, modification, startedAt);
            } catch (RuntimeException exception) {
                log.warn("AI 调用启动失败：appId={}, version={}, type={}, reason={}", appId, versionNo,
                        codeGenType.getValue(), exception.getClass().getSimpleName());
                return Flux.error(exception);
            }
        });
    }

    private Flux<String> generateVueProjectStream(Long appId, String userMessage, Path outputDirectory,
                                                   Long excludedMessageId, Integer versionNo,
                                                   String actorAccount, boolean modification, long startedAt) {
        if (appId == null || outputDirectory == null) {
            return Flux.error(new BusinessException(ErrorCode.PARAMS_ERROR, "Vue 工程生成缺少应用版本目录"));
        }
        int actualVersionNo = versionNo == null ? inferVersionNo(outputDirectory) : versionNo;
        if (actualVersionNo <= 0 || templateService == null || tokenStreamAdapter == null) {
            return Flux.error(new BusinessException(ErrorCode.SYSTEM_ERROR, "Vue 工程生成上下文未初始化"));
        }
        templateService.prepareDefaultTemplate(outputDirectory, appId, actualVersionNo, actorAccount);
        ProjectToolContext context = new ProjectToolContext(appId, actualVersionNo, actorAccount,
                outputDirectory, vueMaxFiles, vueMaxTotalBytes, maxFileSizeBytes);
        ProjectToolBundle toolBundle = new ProjectToolBundle(context);
        if (serviceFactoryProvider == null) {
            return Flux.error(new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "Vue 工程需要配置 AI 模型和工具服务"));
        }
        AiCodeGeneratorServiceFactory factory = serviceFactoryProvider.getIfAvailable();
        if (factory == null) {
            return Flux.error(new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "Vue 工程需要配置 AI 模型和工具服务"));
        }
        AiCodeGeneratorService service = factory.getForVueProject(appId, excludedMessageId, toolBundle);
        dev.langchain4j.service.TokenStream tokenStream;
        try {
            tokenStream = modification
                    ? service.modifyVueProjectCodeStream(appId, userMessage)
                    : service.generateVueProjectCodeStream(appId, userMessage);
        } catch (dev.langchain4j.guardrail.InputGuardrailException exception) {
            // 护轨文案本身面向用户；包装后 SSE 和对话历史能呈现真实拦截原因，而不是通用的模型故障提示。
            throw new BusinessException(ErrorCode.OPERATION_ERROR, guardrailUserMessage(exception), exception);
        }
        if (tokenStream == null) {
            return Flux.error(new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 未返回 Vue 工具流"));
        }
        return tokenStreamAdapter.adapt(tokenStream, toolBundle, context)
                .doFinally(signal -> log.info("AI 调用结束：actor={}, appId={}, version={}, type={}, mode={}, signal={}, durationMs={}",
                        actorAccount, appId, actualVersionNo, CodeGenTypeEnum.VUE_PROJECT.getValue(),
                        modification ? "修改" : "创建", signal,
                        elapsedMillis(startedAt)));
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
                                           Path outputDirectory,
                                           Long appId,
                                           Integer versionNo,
                                           String actorAccount,
                                           boolean modification,
                                           long startedAt) {
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
                    File savedDirectory;
                    if (outputDirectory == null) {
                        savedDirectory = codeFileSaverExecutor.executeSaver(parsedResult, codeGenType);
                    } else if (modification) {
                        // 工作流质检重试或应用迭代可能复用同一目录；先写临时目录再受控替换。
                        savedDirectory = codeFileSaverExecutor.executeSaverReplacing(
                                parsedResult, codeGenType, outputDirectory);
                    } else {
                        savedDirectory = codeFileSaverExecutor.executeSaver(
                                parsedResult, codeGenType, outputDirectory);
                    }
                    log.info("代码保存成功：appId={}, version={}, type={}, directory={}",
                            appId, versionNo, codeGenType.getValue(), savedDirectory.getName());
                    return Flux.empty();
                }))
                .doOnError(error -> log.warn("代码生成或保存失败：type={}, errorType={}",
                        codeGenType.getValue(), error == null ? "未知异常" : error.getClass().getSimpleName()))
                .doFinally(signal -> log.info("AI 调用结束：actor={}, appId={}, version={}, type={}, signal={}, durationMs={}",
                        actorAccount, appId, versionNo, codeGenType.getValue(), signal, elapsedMillis(startedAt)));
    }

    private int inferVersionNo(Path outputDirectory) {
        Matcher matcher = VERSION_DIRECTORY_PATTERN.matcher(outputDirectory.toAbsolutePath().normalize().toString());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    /** 框架异常消息带有 guardrail 类名前缀；只把面向用户的 fatal 文案透出，避免泄露内部类名。 */
    private String guardrailUserMessage(dev.langchain4j.guardrail.InputGuardrailException exception) {
        final String marker = "failed with this message: ";
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "输入内容未通过安全检查，请调整需求描述后重试";
        }
        int index = message.lastIndexOf(marker);
        return index >= 0 && index + marker.length() < message.length()
                ? message.substring(index + marker.length())
                : message;
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
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
