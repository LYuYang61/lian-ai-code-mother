package com.lian.aicode.core.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lian.aicode.ai.model.HtmlCodeResult;
import com.lian.aicode.exception.BusinessException;
import com.lian.aicode.exception.ErrorCode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析单 HTML 代码块，兼容三种模型输出形态：
 * ① ```html 围栏；② 结构化 JSON（{"htmlCode": ..., "description": ...}，可带 ```json 围栏）；
 * ③ 无围栏的裸 HTML。
 *
 * <p>2026-09-25 真机验收发现：模型未被要求结构化输出时也可能自发返回 ```json 围栏包裹的
 * JSON 形态。旧实现只认 ① 和 ③，会把整段原始输出兜底落盘，而完整性守卫会被 JSON 字符串值里
 * 的字面量 {@code </html>} 骗过，导致坏文件被保存并标记为 ready 版本。现在 ② 会被正确提取，
 * 且守卫新增"必须以 {@code <!DOCTYPE} 或 {@code <html} 开头"的形态检查——不合法的形态
 * 一律按生成失败拒绝，绝不原始落盘。</p>
 */
public class HtmlCodeParser implements CodeParser<HtmlCodeResult> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Pattern HTML_CODE_PATTERN = Pattern.compile(
            "```[\\t ]*html[\\t ]*\\R(.*?)```", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern JSON_FENCE_PATTERN = Pattern.compile(
            "```[\\t ]*json[\\t ]*\\R(.*?)```", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * 完整 HTML 文档必然出现 body 或 html 的闭合标签。真机验收发现模型输出可能被截断
     * （只有开头围栏、内容停在样式中间），这类内容必须按生成失败拒绝，不能带着说明文字
     * 和围栏一起落盘成“可用版本”。提示词约束模型“只输出完整代码”是软约束，这里是硬保障。
     */
    private static final Pattern COMPLETE_HTML_PATTERN =
            Pattern.compile("<\\s*/\\s*(?:body|html)\\s*>", Pattern.CASE_INSENSITIVE);

    /** 文档必须以 <!DOCTYPE 或 <html 开头（允许前置空白/BOM）；拦截 JSON、围栏残留等非 HTML 形态。 */
    private static final Pattern HTML_DOCUMENT_START_PATTERN =
            Pattern.compile("\\A[\\s\\uFEFF]*<(?:!DOCTYPE|html)", Pattern.CASE_INSENSITIVE);

    @Override
    public HtmlCodeResult parseCode(String codeContent) {
        HtmlCodeResult result = new HtmlCodeResult();
        if (codeContent == null) {
            return result;
        }
        String htmlCode = extractHtmlCode(codeContent);
        if (htmlCode == null) {
            JsonOutput jsonOutput = extractJsonHtmlCode(codeContent);
            if (jsonOutput != null) {
                result.setDescription(jsonOutput.description());
                htmlCode = jsonOutput.htmlCode();
            }
        }
        String finalCode = (htmlCode == null ? codeContent : htmlCode).strip();
        requireCompleteHtml(finalCode);
        result.setHtmlCode(finalCode);
        return result;
    }

    private String extractHtmlCode(String content) {
        Matcher matcher = HTML_CODE_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 提取结构化 JSON 形态（可带 ```json 围栏）中的 htmlCode/description 字段。
     *
     * <p>JSON 不合法、不是对象、缺少非空 htmlCode 字段时返回 null，交由
     * {@link #requireCompleteHtml} 按生成失败拒绝——不能把半成品 JSON 原始落盘。</p>
     */
    private JsonOutput extractJsonHtmlCode(String content) {
        String candidate = stripJsonFence(content).strip();
        if (!candidate.startsWith("{")) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(candidate);
            JsonNode htmlCode = root.get("htmlCode");
            if (htmlCode == null || !htmlCode.isTextual() || htmlCode.asText().isBlank()) {
                return null;
            }
            JsonNode description = root.get("description");
            return new JsonOutput(htmlCode.asText(),
                    description != null && description.isTextual() ? description.asText() : null);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private String stripJsonFence(String content) {
        Matcher matcher = JSON_FENCE_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1) : content;
    }

    private void requireCompleteHtml(String htmlCode) {
        if (htmlCode == null || htmlCode.isBlank()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "生成内容为空，已拒绝保存，请重试或简化需求描述");
        }
        if (!HTML_DOCUMENT_START_PATTERN.matcher(htmlCode).find()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "生成内容不是以 HTML 文档开头的完整输出（疑似 JSON 或围栏残留），已拒绝保存，请重试或简化需求描述");
        }
        if (!COMPLETE_HTML_PATTERN.matcher(htmlCode).find()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "生成内容不完整（HTML 文档未闭合），已拒绝保存，请重试或简化需求描述");
        }
    }

    /** JSON 形态提取结果：htmlCode 为反转义后的完整 HTML，description 可为 null。 */
    private record JsonOutput(String htmlCode, String description) {
    }
}
