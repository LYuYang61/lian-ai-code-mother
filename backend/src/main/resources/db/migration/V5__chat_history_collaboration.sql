-- 第五期手工迁移脚本：对话历史游标、Redis 记忆恢复、摘要和协作者。
-- 当前项目尚未引入 Flyway/Liquibase；已有 MySQL 数据库升级时请由开发者在确认备份后执行。
-- 新建数据库请直接执行 schema-mysql.sql，无需重复执行本文件。

ALTER TABLE app
    ADD COLUMN deployed_version INT NULL AFTER current_version,
    ADD COLUMN conversation_rounds INT NOT NULL DEFAULT 0 AFTER deployed_version;

ALTER TABLE chat_history
    ADD COLUMN parent_id BIGINT NULL AFTER user_id,
    ADD COLUMN file_list TEXT NULL AFTER version_no,
    MODIFY COLUMN message MEDIUMTEXT NOT NULL,
    ADD KEY idx_history_cursor (app_id, create_time, id, is_delete);

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
