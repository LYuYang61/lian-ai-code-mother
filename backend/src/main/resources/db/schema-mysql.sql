-- 第四期数据库初始化脚本，执行前请先创建并选择 lian_ai_code 数据库。
-- 生产环境建议交给 Flyway/Liquibase 管理版本；本学习阶段保留可读的原始 SQL。

CREATE TABLE IF NOT EXISTS user_account (
    id BIGINT NOT NULL PRIMARY KEY,
    user_account VARCHAR(32) NOT NULL,
    user_password VARCHAR(100) NOT NULL,
    user_name VARCHAR(32) NOT NULL,
    user_avatar VARCHAR(512) NULL,
    user_profile VARCHAR(512) NULL,
    user_role VARCHAR(16) NOT NULL DEFAULT 'user',
    edit_time DATETIME(3) NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    is_delete TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_user_account (user_account),
    KEY idx_user_role (user_role),
    KEY idx_user_delete (is_delete)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS app (
    id BIGINT NOT NULL PRIMARY KEY,
    app_name VARCHAR(64) NOT NULL,
    cover VARCHAR(512) NULL,
    init_prompt TEXT NOT NULL,
    code_gen_type VARCHAR(32) NOT NULL,
    deploy_key VARCHAR(64) NULL,
    deployed_time DATETIME(3) NULL,
    priority INT NOT NULL DEFAULT 0,
    user_id BIGINT NOT NULL,
    visibility VARCHAR(16) NOT NULL DEFAULT 'private',
    category VARCHAR(32) NULL,
    tags VARCHAR(500) NULL,
    generation_status VARCHAR(16) NOT NULL DEFAULT 'draft',
    current_version INT NOT NULL DEFAULT 0,
    deployed_version INT NULL,
    conversation_rounds INT NOT NULL DEFAULT 0,
    generation_message VARCHAR(512) NULL,
    featured_status VARCHAR(16) NOT NULL DEFAULT 'none',
    featured_reason VARCHAR(500) NULL,
    deployment_status VARCHAR(16) NOT NULL DEFAULT 'undeployed',
    edit_time DATETIME(3) NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    is_delete TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_app_deploy_key (deploy_key),
    KEY idx_app_user (user_id, is_delete, create_time),
    KEY idx_app_public (visibility, featured_status, priority, current_version, is_delete),
    KEY idx_app_generation (generation_status, deployment_status, is_delete),
    KEY idx_app_category (category, is_delete),
    CONSTRAINT fk_app_user FOREIGN KEY (user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS app_version (
    id BIGINT NOT NULL PRIMARY KEY,
    app_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    code_gen_type VARCHAR(32) NOT NULL,
    relative_path VARCHAR(255) NOT NULL,
    prompt TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    description VARCHAR(512) NULL,
    created_by BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    is_delete TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_app_version (app_id, version_no),
    KEY idx_version_status (app_id, status, is_delete),
    CONSTRAINT fk_version_app FOREIGN KEY (app_id) REFERENCES app (id),
    CONSTRAINT fk_version_user FOREIGN KEY (created_by) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chat_history (
    id BIGINT NOT NULL PRIMARY KEY,
    -- 以 Java 字符数限制消息；UTF-8 多字节字符可能超过 TEXT 的 64 KiB，使用 MEDIUMTEXT 留出安全余量。
    message MEDIUMTEXT NOT NULL,
    message_type VARCHAR(16) NOT NULL,
    app_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    parent_id BIGINT NULL,
    version_no INT NULL,
    file_list TEXT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    is_delete TINYINT NOT NULL DEFAULT 0,
    KEY idx_history_app (app_id, create_time, is_delete),
    KEY idx_history_cursor (app_id, create_time, id, is_delete),
    KEY idx_history_user (user_id, create_time),
    CONSTRAINT fk_history_app FOREIGN KEY (app_id) REFERENCES app (id),
    CONSTRAINT fk_history_user FOREIGN KEY (user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS app_chat_summary (
    id BIGINT NOT NULL PRIMARY KEY,
    app_id BIGINT NOT NULL,
    summary MEDIUMTEXT NOT NULL,
    covered_until_id BIGINT NULL,
    covered_until_time DATETIME(3) NULL,
    message_count INT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    is_delete TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_summary_app (app_id),
    KEY idx_summary_delete (is_delete),
    CONSTRAINT fk_summary_app FOREIGN KEY (app_id) REFERENCES app (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS app_collaborator (
    id BIGINT NOT NULL PRIMARY KEY,
    app_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'editor',
    invited_by BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    is_delete TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_collaborator_app_user (app_id, user_id, is_delete),
    KEY idx_collaborator_user (user_id, is_delete),
    CONSTRAINT fk_collaborator_app FOREIGN KEY (app_id) REFERENCES app (id),
    CONSTRAINT fk_collaborator_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_collaborator_inviter FOREIGN KEY (invited_by) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
