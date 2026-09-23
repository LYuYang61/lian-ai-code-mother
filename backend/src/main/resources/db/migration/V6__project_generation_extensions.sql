-- 第七期迁移脚本：应用封面截图、源代码下载统计。
-- 现有 MySQL 数据库执行前请先备份；新数据库直接执行 schema-mysql.sql。

ALTER TABLE app
    ADD COLUMN download_count INT NOT NULL DEFAULT 0 AFTER conversation_rounds;
