package com.lian.aicode;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * 学习项目后端启动类。
 *
 * <p>当前保持单体结构，把用户、应用和 AI 生成能力放在同一个可验证的学习闭环中；后续再按业务边界拆分。
 * {@code @EnableCaching} 支撑第十期的精选列表 Redis 旁路缓存；缓存管理器由
 * {@code RedisCacheManagerConfig} 显式提供，测试与关闭开关场景退化为 NoOp。</p>
 */
@EnableCaching
@SpringBootApplication
@MapperScan("com.lian.aicode.mapper")
public class LianAiCodeMotherApplication {

    public static void main(String[] args) {
        SpringApplication.run(LianAiCodeMotherApplication.class, args);
    }
}
