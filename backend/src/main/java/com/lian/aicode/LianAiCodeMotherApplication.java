package com.lian.aicode;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 学习项目后端启动类。
 *
 * <p>当前保持单体结构，把用户、应用和 AI 生成能力放在同一个可验证的学习闭环中；后续再按业务边界拆分。</p>
 */
@SpringBootApplication
@MapperScan("com.lian.aicode.mapper")
public class LianAiCodeMotherApplication {

    public static void main(String[] args) {
        SpringApplication.run(LianAiCodeMotherApplication.class, args);
    }
}
