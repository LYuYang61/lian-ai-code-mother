package com.lian.aicode.core;

import com.lian.aicode.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实模型链路验收测试，对应教程第 3 期“真实调用观察效果”的环节。
 *
 * <p>与离线单元测试的区别：本测试注入真实 AI Service Bean，会向 DeepSeek 发起付费请求，
 * 用于验证密钥有效性、模型名、结构化输出和流式链路在真实网络下的表现；
 * 模型输出内容本身不稳定，断言只覆盖“文件生成且可打开”的验收标准。</p>
 *
 * <p>安全开关：只有环境变量 AI_REAL_CALL_TEST=true 时才会执行，
 * 因此 mvnw test 全量回归、CI 和无密钥环境永远不会意外产生模型费用。
 * 在 IDEA 的该测试运行配置中添加 AI_REAL_CALL_TEST=true 后单独运行本类；
 * 密钥经系统环境变量 DEEPSEEK_API_KEY 传入，测试属性只做占位引用，不保存明文。</p>
 */
@Timeout(180)
@EnabledIfEnvironmentVariable(named = "AI_REAL_CALL_TEST", matches = "true")
@SpringBootTest(properties = {
        "langchain4j.open-ai.chat-model.api-key=${DEEPSEEK_API_KEY}",
        "langchain4j.open-ai.streaming-chat-model.api-key=${DEEPSEEK_API_KEY}"
})
@ActiveProfiles("test")
class AiCodeGeneratorRealCallTest {

    @Autowired
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    /** 读取应用实际解析后的输出根目录，避免测试自行拼路径与配置脱节。 */
    @Value("${app.code-output-root}")
    private String codeOutputRoot;

    @Test
    void syncHtmlGenerationSavesBrowsablePage() throws Exception {
        File directory = aiCodeGeneratorFacade.generateAndSaveCode(
                "一个红色大标题显示 Hello World 的简单页面", CodeGenTypeEnum.HTML);

        File htmlFile = new File(directory, "index.html");
        assertTrue(htmlFile.isFile(), "应生成 index.html");
        String html = Files.readString(htmlFile.toPath());
        assertFalse(html.isBlank(), "HTML 内容不能为空");
        assertTrue(html.toLowerCase().contains("<html"), "应生成完整 HTML 文档");
        System.out.println("【真实验收】单文件生成目录：" + directory.getAbsolutePath());
    }

    @Test
    void syncMultiFileGenerationSavesThreeFiles() throws Exception {
        File directory = aiCodeGeneratorFacade.generateAndSaveCode(
                "一个蓝色标题带留言输入框的简单页面", CodeGenTypeEnum.MULTI_FILE);

        assertTrue(new File(directory, "index.html").isFile());
        assertTrue(new File(directory, "style.css").isFile());
        assertTrue(new File(directory, "script.js").isFile());
        // 设计上允许 CSS/JS 为空，只强制 HTML 非空，与保存器的必填校验一致。
        assertFalse(Files.readString(new File(directory, "index.html").toPath()).isBlank());
        System.out.println("【真实验收】多文件生成目录：" + directory.getAbsolutePath());
    }

    @Test
    void streamGenerationSavesAfterCompletion() throws Exception {
        // 门面流式方法不返回目录，用“调用前后输出根目录的差集”定位本次生成的目录。
        Path outputRoot = Path.of(codeOutputRoot);
        Set<Path> dirsBefore = listGeneratedDirs(outputRoot);

        // collectList().block() 等待整个 Flux 结束；门面用 concatWith 保证保存完成后才结束，
        // 因此 block 返回时文件应已落盘，这正是本期“流结束才保存”的验收点。
        List<String> chunks = aiCodeGeneratorFacade.generateAndSaveCodeStream(
                        "一个绿色标题显示待办清单的简单页面", CodeGenTypeEnum.HTML)
                .collectList()
                .block();

        assertNotNull(chunks);
        assertFalse(chunks.isEmpty(), "流式应返回至少一个片段");

        Set<Path> newDirs = listGeneratedDirs(outputRoot).stream()
                .filter(path -> !dirsBefore.contains(path))
                .collect(Collectors.toSet());
        assertFalse(newDirs.isEmpty(), "流结束后应产生新的生成目录");
        Path generated = newDirs.iterator().next();
        File htmlFile = generated.resolve("index.html").toFile();
        assertTrue(htmlFile.isFile(), "流式生成的 index.html 应存在");
        assertFalse(Files.readString(htmlFile.toPath()).isBlank());
        System.out.println("【真实验收】流式生成目录：" + generated.toAbsolutePath()
                + "，共收到 " + chunks.size() + " 个片段");
    }

    private Set<Path> listGeneratedDirs(Path outputRoot) throws Exception {
        if (!Files.isDirectory(outputRoot)) {
            return Set.of();
        }
        try (Stream<Path> paths = Files.list(outputRoot)) {
            return paths.filter(Files::isDirectory).collect(Collectors.toSet());
        }
    }
}
