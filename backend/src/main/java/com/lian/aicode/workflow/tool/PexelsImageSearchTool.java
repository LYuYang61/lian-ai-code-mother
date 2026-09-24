package com.lian.aicode.workflow.tool;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lian.aicode.workflow.model.ImageCategoryEnum;
import com.lian.aicode.workflow.model.ImageResource;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** Pexels 内容图片搜索工具；未配置密钥时返回空列表并让工作流继续。 */
@Slf4j
@Component
public class PexelsImageSearchTool {

    private static final String API_URL = "https://api.pexels.com/v1/search";
    private static final int MAX_RESULTS = 12;
    private static final int MAX_QUERY_LENGTH = 120;

    @Value("${PEXELS_API_KEY:}")
    private String apiKey;

    @Tool("搜索与网页内容相关的照片，用于产品、场景或文章内容展示")
    public List<ImageResource> searchContentImages(@P("搜索关键词") String query) {
        String safeQuery = trimQuery(query);
        if (!StringUtils.hasText(apiKey)) {
            log.info("跳过 Pexels 图片搜索：reason=PEXELS_API_KEY 未配置");
            return List.of();
        }
        if (!StringUtils.hasText(safeQuery)) {
            return List.of();
        }
        long startedAt = System.nanoTime();
        try (HttpResponse response = HttpRequest.get(API_URL)
                .header("Authorization", apiKey.trim())
                .form("query", safeQuery)
                .form("per_page", MAX_RESULTS)
                .form("page", 1)
                .timeout(15_000)
                .execute()) {
            if (!response.isOk()) {
                log.warn("Pexels 图片搜索失败：status={}, queryLength={}, durationMs={}",
                        response.getStatus(), safeQuery.length(), elapsedMillis(startedAt));
                return List.of();
            }
            JSONObject result = JSONUtil.parseObj(response.body());
            JSONArray photos = result.getJSONArray("photos");
            List<ImageResource> resources = new ArrayList<>();
            if (photos != null) {
                for (int i = 0; i < Math.min(photos.size(), MAX_RESULTS); i++) {
                    JSONObject photo = photos.getJSONObject(i);
                    JSONObject source = photo == null ? null : photo.getJSONObject("src");
                    String url = source == null ? null : source.getStr("medium");
                    if (StringUtils.hasText(url)) {
                        resources.add(ImageResource.builder()
                                .category(ImageCategoryEnum.CONTENT)
                                .description(trimDescription(photo.getStr("alt", safeQuery)))
                                .url(url.trim())
                                .build());
                    }
                }
            }
            log.info("Pexels 图片搜索完成：queryLength={}, count={}, durationMs={}, result=成功",
                    safeQuery.length(), resources.size(), elapsedMillis(startedAt));
            return resources;
        } catch (Exception exception) {
            log.warn("Pexels 图片搜索异常：queryLength={}, reason={}, durationMs={}", safeQuery.length(),
                    exception.getClass().getSimpleName(), elapsedMillis(startedAt));
            return List.of();
        }
    }

    private String trimQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return "";
        }
        String value = query.trim();
        return value.substring(0, Math.min(value.length(), MAX_QUERY_LENGTH));
    }

    private String trimDescription(String description) {
        String value = StringUtils.hasText(description) ? description.trim() : "网页内容图片";
        return value.substring(0, Math.min(value.length(), 200));
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
