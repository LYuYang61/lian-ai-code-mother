package com.lian.aicode.config;

import com.lian.aicode.common.BaseResponse;
import com.lian.aicode.common.ResultUtils;
import com.lian.aicode.model.vo.AppVO;
import com.lian.aicode.model.vo.PageResult;
import com.lian.aicode.model.vo.UserVO;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 精选列表缓存值的离线往返测试。
 *
 * <p>教程明确提示 JSON 值序列化可能出现“无法反序列化”的风险；本测试用与生产
 * {@link RedisCacheManagerConfig#buildCacheObjectMapper()} 完全相同的配置，验证
 * {@code BaseResponse<PageResult<AppVO>>} 嵌套泛型（含 owner 嵌套对象与记录列表）
 * 在序列化后能还原为正确的类型层级。真实 Redis 的键名/TTL 与第二页翻页命中场景
 * 仍归真机验收。</p>
 */
class RedisCacheValueRoundTripTest {

    @Test
    void featuredPageResponseRoundTripsWithTypeSafety() {
        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(RedisCacheManagerConfig.buildCacheObjectMapper());

        AppVO app = AppVO.builder()
                .id(101L)
                .appName("任务工作台")
                .codeGenType("multi_file")
                .visibility("public")
                .priority(99)
                .currentVersion(2)
                .createTime("2026-09-25T10:00:00")
                .owner(UserVO.builder().id(7L).userName("演示用户").build())
                .build();
        PageResult<AppVO> page = new PageResult<>(List.of(app), 2, 12, 25, 3);
        BaseResponse<PageResult<AppVO>> response = ResultUtils.success(page);

        byte[] serialized = serializer.serialize(response);
        assertNotNull(serialized);

        Object restored = serializer.deserialize(serialized);
        BaseResponse<?> restoredResponse = assertInstanceOf(BaseResponse.class, restored,
                "顶层应还原为 BaseResponse 而不是 LinkedHashMap");
        PageResult<?> restoredPage = assertInstanceOf(PageResult.class, restoredResponse.getData(),
                "data 应还原为 PageResult 而不是 LinkedHashMap");
        assertEquals(2L, restoredPage.getPageNum());
        assertEquals(25L, restoredPage.getTotal());
        Object restoredApp = assertInstanceOf(AppVO.class, restoredPage.getRecords().get(0),
                "记录应还原为 AppVO 而不是 LinkedHashMap");
        assertEquals("任务工作台", ((AppVO) restoredApp).getAppName());
        assertEquals(Long.valueOf(7L), ((AppVO) restoredApp).getOwner().getId());
    }
}
