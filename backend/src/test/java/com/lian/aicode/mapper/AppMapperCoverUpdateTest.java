package com.lian.aicode.mapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 通过 H2 真正执行 MyBatis 动态 SQL，覆盖旧封面为空和非空两种脚本分支。 */
@SpringBootTest
@ActiveProfiles("test")
class AppMapperCoverUpdateTest {

    private static final AtomicLong APP_ID_SEQUENCE = new AtomicLong(8_000_000_000_000_000L);

    @Autowired
    private AppMapper appMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long appId;

    @BeforeEach
    void insertApp() {
        appId = APP_ID_SEQUENCE.incrementAndGet();
        jdbcTemplate.update("""
                        INSERT INTO app (id, app_name, init_prompt, code_gen_type, user_id, current_version, cover)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                appId, "mapper-cover-test", "test", "html", 1L, 7, null);
    }

    @AfterEach
    void deleteApp() {
        jdbcTemplate.update("DELETE FROM app WHERE id = ?", appId);
    }

    @Test
    void updatesOnlyWhenVersionAndPreviousCoverStillMatch() {
        assertThat(appMapper.updateCoverIfCurrentVersion(appId, 7, null, "https://cover.example/v1.jpg"))
                .isEqualTo(1);
        assertThat(readCover()).isEqualTo("https://cover.example/v1.jpg");

        assertThat(appMapper.updateCoverIfCurrentVersion(appId, 7, "https://stale.example/old.jpg",
                "https://cover.example/should-not-write.jpg"))
                .isZero();
        assertThat(readCover()).isEqualTo("https://cover.example/v1.jpg");

        assertThat(appMapper.updateCoverIfCurrentVersion(appId, 7, "https://cover.example/v1.jpg",
                "https://cover.example/v2.jpg"))
                .isEqualTo(1);
        assertThat(readCover()).isEqualTo("https://cover.example/v2.jpg");

        assertThat(appMapper.updateCoverIfCurrentVersion(appId, 8, "https://cover.example/v2.jpg",
                "https://cover.example/stale-version.jpg"))
                .isZero();
        assertThat(readCover()).isEqualTo("https://cover.example/v2.jpg");
    }

    private String readCover() {
        return jdbcTemplate.queryForObject("SELECT cover FROM app WHERE id = ?", String.class, appId);
    }
}
