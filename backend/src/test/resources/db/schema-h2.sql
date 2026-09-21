CREATE TABLE IF NOT EXISTS user_account (
    id BIGINT PRIMARY KEY,
    user_account VARCHAR(32) NOT NULL,
    user_password VARCHAR(100) NOT NULL,
    user_name VARCHAR(32) NOT NULL,
    user_avatar VARCHAR(512),
    user_profile VARCHAR(512),
    user_role VARCHAR(16) NOT NULL DEFAULT 'user',
    edit_time TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_delete INT NOT NULL DEFAULT 0,
    UNIQUE (user_account)
);

CREATE TABLE IF NOT EXISTS app (
    id BIGINT PRIMARY KEY,
    app_name VARCHAR(64) NOT NULL,
    cover VARCHAR(512),
    init_prompt VARCHAR(10000) NOT NULL,
    code_gen_type VARCHAR(32) NOT NULL,
    deploy_key VARCHAR(64),
    deployed_time TIMESTAMP,
    priority INT NOT NULL DEFAULT 0,
    user_id BIGINT NOT NULL,
    visibility VARCHAR(16) NOT NULL DEFAULT 'private',
    category VARCHAR(32),
    tags VARCHAR(500),
    generation_status VARCHAR(16) NOT NULL DEFAULT 'draft',
    current_version INT NOT NULL DEFAULT 0,
    deployed_version INT,
    conversation_rounds INT NOT NULL DEFAULT 0,
    generation_message VARCHAR(512),
    featured_status VARCHAR(16) NOT NULL DEFAULT 'none',
    featured_reason VARCHAR(500),
    deployment_status VARCHAR(16) NOT NULL DEFAULT 'undeployed',
    edit_time TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_delete INT NOT NULL DEFAULT 0,
    UNIQUE (deploy_key)
);

CREATE TABLE IF NOT EXISTS app_version (
    id BIGINT PRIMARY KEY,
    app_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    code_gen_type VARCHAR(32) NOT NULL,
    relative_path VARCHAR(255) NOT NULL,
    prompt VARCHAR(10000) NOT NULL,
    status VARCHAR(16) NOT NULL,
    description VARCHAR(512),
    created_by BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_delete INT NOT NULL DEFAULT 0,
    UNIQUE (app_id, version_no)
);

CREATE TABLE IF NOT EXISTS chat_history (
    id BIGINT PRIMARY KEY,
    message CLOB NOT NULL,
    message_type VARCHAR(16) NOT NULL,
    app_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    parent_id BIGINT,
    version_no INT,
    file_list CLOB,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_delete INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS app_chat_summary (
    id BIGINT PRIMARY KEY,
    app_id BIGINT NOT NULL,
    summary CLOB NOT NULL,
    covered_until_id BIGINT,
    covered_until_time TIMESTAMP,
    message_count INT NOT NULL DEFAULT 0,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_delete INT NOT NULL DEFAULT 0,
    UNIQUE (app_id)
);

CREATE TABLE IF NOT EXISTS app_collaborator (
    id BIGINT PRIMARY KEY,
    app_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'editor',
    invited_by BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_delete INT NOT NULL DEFAULT 0,
    UNIQUE (app_id, user_id, is_delete)
);
