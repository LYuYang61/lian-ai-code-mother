package com.lian.aicode.utils;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;

/**
 * 缓存 key 生成工具类（教程 11 期同款思路：JSON 序列化 + MD5）。
 *
 * <p>把复杂查询对象转成稳定的 JSON 字符串再取 MD5，既保证相同查询生成相同 key，
 * 又避免把长查询条件直接拼进 Redis key。null 输入也有固定 key，便于统一调用。</p>
 */
public final class CacheKeyUtils {

    private CacheKeyUtils() {
    }

    /**
     * 根据对象生成缓存 key（JSON + MD5）。
     *
     * @param obj 参与 key 的对象，通常为查询请求 DTO
     * @return 固定长度的 MD5 缓存 key
     */
    public static String generateKey(Object obj) {
        if (obj == null) {
            return DigestUtil.md5Hex("null");
        }
        return DigestUtil.md5Hex(JSONUtil.toJsonStr(obj));
    }
}
