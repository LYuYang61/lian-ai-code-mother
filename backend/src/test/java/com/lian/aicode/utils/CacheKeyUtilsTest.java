package com.lian.aicode.utils;

import cn.hutool.json.JSONUtil;
import com.lian.aicode.model.dto.app.AppQueryRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 缓存 key 生成工具的稳定性与区分度测试。 */
class CacheKeyUtilsTest {

    @Test
    void nullObjectGeneratesStableKey() {
        assertEquals(CacheKeyUtils.generateKey(null), CacheKeyUtils.generateKey(null));
        assertTrue(CacheKeyUtils.generateKey(null).length() == 32, "MD5 key 应为 32 位");
    }

    @Test
    void sameQueryGeneratesSameKey() {
        AppQueryRequest first = new AppQueryRequest();
        first.setPageNum(1);
        first.setPageSize(12);
        first.setSearchText("任务");
        AppQueryRequest second = new AppQueryRequest();
        second.setPageNum(1);
        second.setPageSize(12);
        second.setSearchText("任务");
        assertEquals(CacheKeyUtils.generateKey(first), CacheKeyUtils.generateKey(second));
    }

    @Test
    void differentQueryGeneratesDifferentKey() {
        AppQueryRequest first = new AppQueryRequest();
        first.setPageNum(1);
        AppQueryRequest second = new AppQueryRequest();
        second.setPageNum(2);
        assertNotEquals(CacheKeyUtils.generateKey(first), CacheKeyUtils.generateKey(second));
    }

    @Test
    void keyIsMd5OfJsonRepresentation() {
        AppQueryRequest request = new AppQueryRequest();
        request.setPageNum(3);
        assertEquals(cn.hutool.crypto.digest.DigestUtil.md5Hex(JSONUtil.toJsonStr(request)),
                CacheKeyUtils.generateKey(request));
    }
}
