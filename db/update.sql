-- v4.5.0
ALTER TABLE base_api_info ADD draft_content longtext COMMENT '接口暂存内容';
-- v5.0.0
ALTER TABLE base_api_info ADD transaction_enabled int DEFAULT 0 COMMENT '是否开启事务 1/0';
ALTER TABLE base_api_info ADD rate_limit_window_seconds int DEFAULT NULL COMMENT '限流时间窗口秒数';
ALTER TABLE base_api_info ADD rate_limit_count int DEFAULT NULL COMMENT '限流窗口请求次数';

-- v6.0.0
ALTER TABLE base_api_info ADD plugin_config text COMMENT '数据插件配置JSON';
-- PostgreSQL 方言（PostgreSQL 库请执行以下语句，MySQL 请忽略）
-- ALTER TABLE base_api_info ADD COLUMN plugin_config TEXT;
-- COMMENT ON COLUMN base_api_info.plugin_config IS '数据插件配置JSON';

-- v6.3.1
ALTER TABLE base_api_info ADD script_timeout_seconds int DEFAULT 0 COMMENT '脚本超时时间（秒），0或空使用默认30秒';
-- PostgreSQL 方言（PostgreSQL 库请执行以下语句，MySQL 请忽略）
-- ALTER TABLE base_api_info ADD COLUMN script_timeout_seconds INTEGER DEFAULT 0;
-- COMMENT ON COLUMN base_api_info.script_timeout_seconds IS '脚本超时时间（秒），0或空使用默认30秒';

-- 索引优化 2026-08-11
-- base_api_info 表新增索引
ALTER TABLE base_api_info ADD INDEX idx_api_status_release_time (api_status, release_time);
ALTER TABLE base_api_info ADD INDEX idx_group_id (group_id);
ALTER TABLE base_api_info ADD INDEX idx_parent_id_status (parent_id, api_status);
ALTER TABLE base_api_info ADD INDEX idx_create_by (create_by);

-- base_api_log 表新增索引
ALTER TABLE base_api_log ADD INDEX idx_api_id (api_id);
ALTER TABLE base_api_log ADD INDEX idx_request_time (request_time);
ALTER TABLE base_api_log ADD INDEX idx_app_name (app_name);
ALTER TABLE base_api_log ADD INDEX idx_api_id_time (api_id, request_time);

-- base_api_param 表新增索引
ALTER TABLE base_api_param ADD INDEX idx_api_id (api_id);
ALTER TABLE base_api_param ADD INDEX idx_api_id_model (api_id, param_model);
