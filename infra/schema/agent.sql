-- agent-runtime 数据库引导脚本。表结构由 agent-runtime/app/init_db.py(ORM)创建,
-- 本文件不定义表,避免双源漂移。
CREATE DATABASE IF NOT EXISTS agent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;