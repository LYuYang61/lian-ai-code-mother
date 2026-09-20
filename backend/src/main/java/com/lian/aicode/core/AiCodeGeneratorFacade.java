package com.lian.aicode.core;

import com.lian.aicode.ai.AiCodeGeneratorService;
import com.lian.aicode.core.parser.CodeParserExecutor;
import com.lian.aicode.core.saver.CodeFileSaverExecutor;
import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;
import com.lian.aicode.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * AI 代码生成门面：统一编排模型调用、流式收集、解析和固定文件落盘。
 *
 * <p>流式链路在所有片段完成后才执行解析和保存；保存失败会作为流错误传播，避免前端收到“完成”
 * 却拿不到文件。同步链路则直接返回已保存目录。</p>
 */
@Slf4j
@Service
public class AiCodeGeneratorFacade {

    private final Supplier<AiCodeGeneratorService> aiServiceSupplier;
    private final CodeParserExecutor codeParserExecutor;
    private final CodeFileSaverExecutor codeFileSaverExecutor;

    /** Spring 使用该构造器；没有 API Key 时 provider 为空，但基础应用仍可启动。 */
    @Autowired
    public AiCodeGeneratorFacade(ObjectProvider<AiCodeGeneratorService> aiServiceProvider,
                                 CodeParserExecutor codeParserExecutor,
                                 CodeFileSaverExecutor codeFileSaverExecutor) {
        this.aiServiceSupplier = () -> aiServiceProvider.getIfAvailable(() -> {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "AI 模型未配置，请设置 DEEPSEEK_API_KEY 并启用 local profile");
        });
        this.codeParserExecutor = codeParserExecutor;
        this.codeFileSaverExecutor = codeFileSaverExecutor;
    }

    /** 供不依赖 Spring 上下文的单元测试使用。 */
    public AiCodeGeneratorFacade(AiCodeGeneratorService aiCodeGeneratorService,
                                 CodeParserExecutor codeParserExecutor,
                                 CodeFileSaverExecutor codeFileSaverExecutor) {
        this.aiServiceSupplier = () -> Objects.requireNonNull(aiCodeGeneratorService);
        this.codeParserExecutor = codeParserExecutor;
        this.codeFileSaverExecutor = codeFileSaverExecutor;
    }

    /** 根据类型同步生成并保存代码。 */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenType) {
        validateRequest(userMessage, codeGenType);
        AiCodeGeneratorService service = aiServiceSupplier.get();
        return switch (codeGenType) {
            case HTML -> codeFileSaverExecutor.executeSaver(
                    service.generateHtmlCode(userMessage), CodeGenTypeEnum.HTML);
            case MULTI_FILE -> codeFileSaverExecutor.executeSaver(
                    service.generateMultiFileCode(userMessage), CodeGenTypeEnum.MULTI_FILE);
        };
    }

    /** 为应用版本生成并保存到指定目录。 */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenType, java.nio.file.Path outputDirectory) {
        validateRequest(userMessage, codeGenType);
        AiCodeGeneratorService service = aiServiceSupplier.get();
        Object result = switch (codeGenType) {
            case HTML -> service.generateHtmlCode(userMessage);
            case MULTI_FILE -> service.generateMultiFileCode(userMessage);
        };
        return codeFileSaverExecutor.executeSaver(result, codeGenType, outputDirectory);
    }

    /**
     * 根据类型生成并保存代码，同时把模型文本片段实时返回给调用方。
     * 最后的保存动作不产生额外数据，只在成功或失败时结束该 Flux。
     */
    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenType) {
        return generateAndSaveCodeStream(userMessage, codeGenType, null);
    }

    /** 为应用版本流式生成；outputDirectory 为空时保持基础阶段的随机目录行为。 */
    public Flux<String> generateAndSaveCodeStream(String userMessage,
                                                   CodeGenTypeEnum codeGenType,
                                                   java.nio.file.Path outputDirectory) {
        validateRequest(userMessage, codeGenType);
        return Flux.defer(() -> {
            AiCodeGeneratorService service = aiServiceSupplier.get();
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

    /**
     * 收集模型流式输出，最终解析并保存代码。
     * @param codeStream
     * @param codeGenType
     * @return
     */
    private Flux<String> processCodeStream(Flux<String> codeStream,
                                           CodeGenTypeEnum codeGenType,
                                           java.nio.file.Path outputDirectory) {
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
                .doOnError(error -> log.warn("代码生成或保存失败：type={}, reason={}",
                        codeGenType.getValue(), error.getMessage()));
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
