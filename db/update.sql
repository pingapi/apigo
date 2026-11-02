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
-- 早期开发库若已按旧脚本建成 script_timeout_millis，请改用重命名（MySQL / PostgreSQL 通用）
-- ALTER TABLE base_api_info RENAME COLUMN script_timeout_millis TO script_timeout_seconds;

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

-- v6.4.1 安全加固：应用-API 授权关系去重
-- 绑定接口前已校验归属，这里保证同一条 (app_id, api_id) 只有一行，避免历史脏数据导致重复授权
DELETE t1 FROM base_app_api t1
INNER JOIN base_app_api t2
WHERE t1.app_id = t2.app_id
  AND t1.api_id = t2.api_id
  AND t1.id > t2.id;
-- PostgreSQL 方言
-- DELETE FROM base_app_api a USING base_app_api b
-- WHERE a.app_id = b.app_id AND a.api_id = b.api_id AND a.id > b.id;

ALTER TABLE base_app_api ADD UNIQUE KEY uk_app_api (app_id, api_id);
-- PostgreSQL 方言
-- CREATE UNIQUE INDEX uk_app_api ON base_app_api (app_id, api_id);

-- v6.4.2 鉴权类型归一：运行时不再对未知值放行（fail-closed）
-- 历史别名归一为新枚举；auth_type 为空的存量数据保持原样（运行时会明确拒绝并提示补配置），
-- 如确认要放开请按需执行：UPDATE base_api_info SET auth_type='none' WHERE auth_type IS NULL OR auth_type='';
UPDATE base_api_info SET auth_type = 'app_code' WHERE auth_type = 'code';
UPDATE base_api_info SET auth_type = 'app_secret' WHERE auth_type = 'secret';
