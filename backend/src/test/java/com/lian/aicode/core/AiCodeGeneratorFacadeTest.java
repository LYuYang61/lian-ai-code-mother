package com.lian.aicode.core;

import com.lian.aicode.ai.AiCodeGeneratorService;
import com.lian.aicode.ai.model.HtmlCodeResult;
import com.lian.aicode.core.parser.CodeParserExecutor;
import com.lian.aicode.core.saver.CodeFileSaverExecutor;
import com.lian.aicode.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiCodeGeneratorFacadeTest {

    @TempDir
    Path tempDir;

    @Test
    void synchronouslyGeneratesAndSavesHtml() throws Exception {
        AiCodeGeneratorService service = mock(AiCodeGeneratorService.class);
        HtmlCodeResult result = new HtmlCodeResult();
        result.setHtmlCode("<!doctype html><html><body>ok</body></html>");
        when(service.generateHtmlCode("做一个简单页面")).thenReturn(result);

        AiCodeGeneratorFacade facade = newFacade(service);
        Path directory = facade.generateAndSaveCode("做一个简单页面", CodeGenTypeEnum.HTML).toPath();

        assertEquals(result.getHtmlCode(), Files.readString(directory.resolve("index.html")));
    }

    @Test
    void streamsContentAndSavesAfterCompletion() throws Exception {
        AiCodeGeneratorService service = mock(AiCodeGeneratorService.class);
        when(service.generateHtmlCodeStream("生成页面"))
                .thenReturn(Flux.just("说明\n", "```html\n<html>", "</html>\n```"));

        AiCodeGeneratorFacade facade = newFacade(service);
        List<String> chunks = facade.generateAndSaveCodeStream("生成页面", CodeGenTypeEnum.HTML)
                .collectList()
                .block();

        assertEquals(3, chunks.size());
        Path outputDirectory;
        try (Stream<Path> paths = Files.list(tempDir)) {
            outputDirectory = paths.findFirst().orElseThrow();
        }
        assertEquals("<html></html>", Files.readString(outputDirectory.resolve("index.html")));
    }

    @Test
    void rejectsBlankUserMessageBeforeCallingModel() {
        AiCodeGeneratorService service = mock(AiCodeGeneratorService.class);
        AiCodeGeneratorFacade facade = newFacade(service);

        assertThrows(RuntimeException.class,
                () -> facade.generateAndSaveCode("  ", CodeGenTypeEnum.HTML));
    }

    private AiCodeGeneratorFacade newFacade(AiCodeGeneratorService service) {
        CodeFileSaverExecutor saverExecutor = new CodeFileSaverExecutor(tempDir);
        return new AiCodeGeneratorFacade(service, new CodeParserExecutor(), saverExecutor);
    }
}
