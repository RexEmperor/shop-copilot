-- agent-runtime 的会话存储。字符集必须是 utf8mb4:
-- 助手回复里已经出现 emoji 🙂,3 字节的老 utf8 存不下 4 字节字符,会直接报错或乱码
CREATE DATABASE IF NOT EXISTS agent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent.chat_session (
                                                  id         VARCHAR(64) PRIMARY KEY,          -- 即 session_id
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
    );

CREATE TABLE IF NOT EXISTS agent.chat_message (
                                                  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
                                                  session_id        VARCHAR(64) NOT NULL,
    role              VARCHAR(16) NOT NULL,
    content           TEXT NOT NULL,
    model             VARCHAR(64) NULL,           -- assistant 消息记录用的模型
    prompt_tokens     INT NULL,
    completion_tokens INT NULL,
    elapsed_ms        INT NULL,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_session (session_id, id)
    ) CHARACTER SET utf8mb4;