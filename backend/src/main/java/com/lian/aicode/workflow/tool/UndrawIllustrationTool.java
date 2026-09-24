package com.lian.aicode.workflow.tool;

import cn.hutool.core.util.URLUtil;
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
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** unDraw 插画搜索工具。unDraw 页面接口可能变化，因此失败时只降级为空素材。 */
@Slf4j
@Component
public class UndrawIllustrationTool {

    private static final String API_URL = "https://undraw.co/_next/data/mMWmJSt23qpgo8cLTD_pB/search/%s.json?term=%s";
    private static final int MAX_RESULTS = 12;

    @Tool("搜索适合网页装饰和空状态的 unDraw 插画")
    public List<ImageResource> searchIllustrations(@P("插画搜索关键词") String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        String safeQuery = query.trim().substring(0, Math.min(query.trim().length(), 100));
        String encoded = URLUtil.encode(safeQuery);
        long startedAt = System.nanoTime();
        try (HttpResponse response = HttpRequest.get(String.format(API_URL, encoded, encoded))
                .timeout(15_000)
                .execute()) {
            if (!response.isOk()) {
                log.warn("unDraw 插画搜索失败：status={}, queryLength={}, durationMs={}",
                        response.getStatus(), safeQuery.length(), elapsedMillis(startedAt));
                return List.of();
            }
            JSONObject pageProps = JSONUtil.parseObj(response.body()).getJSONObject("pageProps");
            JSONArray results = pageProps == null ? null : pageProps.getJSONArray("initialResults");
            List<ImageResource> resources = new ArrayList<>();
            if (results != null) {
                for (int i = 0; i < Math.min(results.size(), MAX_RESULTS); i++) {
                    JSONObject illustration = results.getJSONObject(i);
                    String media = illustration == null ? null : illustration.getStr("media");
                    if (StringUtils.hasText(media)) {
                        resources.add(ImageResource.builder()
                                .category(ImageCategoryEnum.ILLUSTRATION)
                                .description(illustration.getStr("title", "网页装饰插画"))
                                .url(media.trim())
                                .build());
                    }
                }
            }
            log.info("unDraw 插画搜索完成：queryLength={}, count={}, durationMs={}, result=成功",
                    safeQuery.length(), resources.size(), elapsedMillis(startedAt));
            return resources;
        } catch (Exception exception) {
            log.warn("unDraw 插画搜索异常：queryLength={}, reason={}, durationMs={}", safeQuery.length(),
                    exception.getClass().getSimpleName(), elapsedMillis(startedAt));
            return List.of();
        }
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
