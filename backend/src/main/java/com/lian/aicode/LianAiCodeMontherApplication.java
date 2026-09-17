package com.lian.aicode;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 学习项目后端启动类。
 *
 * <p>当前保持单体结构，后续期次再按业务边界逐步增加用户、应用和 AI 能力。</p>
 */
@SpringBootApplication
public class LianAiCodeMontherApplication {

    public static void main(String[] args) {
        SpringApplication.run(LianAiCodeMontherApplication.class, args);
    }
}
