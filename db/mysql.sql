-- 创建库，mysql8.0+以上版本
CREATE DATABASE IF NOT EXISTS apigo default charset utf8mb4 COLLATE utf8mb4_0900_ai_ci;
-- 建表
use apigo;
-- 1.0
CREATE TABLE `base_api_info` (
                                 `api_id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
                                 `api_name` varchar(512)  DEFAULT NULL,
                                 `api_path` varchar(512)  DEFAULT NULL COMMENT 'API路径',
                                 `api_type` varchar(30)  DEFAULT NULL COMMENT 'API类型：sql、table',
                                 `api_method` varchar(30)  DEFAULT NULL COMMENT '请求方式 get、post、put、delete、aptch',
                                 `auth_type` varchar(50)  DEFAULT NULL COMMENT '授权类型：none、code、secret',
                                 `enabled` int DEFAULT NULL COMMENT '开放启用 1/0',
                                 `api_status` varchar(50)  DEFAULT NULL COMMENT 'API状态：编辑edit、审批audit、发布release、销毁destroy',
                                 `group_id` int  DEFAULT NULL COMMENT '分组ID',
                                 `parent_id` bigint DEFAULT NULL COMMENT '父级关联Id',
                                 `tenant_id` varchar(128)  DEFAULT NULL COMMENT '租户ID',
                                 `page_setup` int DEFAULT NULL COMMENT '分页设置，不分页：0, 分页：1',
                                 `transaction_enabled` int DEFAULT 0 COMMENT '是否开启事务 1/0',
                                 `rate_limit_window_seconds` int DEFAULT NULL COMMENT '限流时间窗口秒数',
                                 `rate_limit_count` int DEFAULT NULL COMMENT '限流窗口请求次数',
                                 `script_timeout_seconds` int DEFAULT 0 COMMENT '脚本超时时间（秒），0或空使用默认30秒',
                                 `sql_type` varchar(20) DEFAULT NULL COMMENT 'SQL执行类型（select、insert、update、delete）',
                                 `result_type` varchar(30) DEFAULT NULL COMMENT '返回结果类型： one、array、excel',
                                 `sql_script` text COMMENT 'SQL脚本',
                                 `show_sql_script` int DEFAULT NULL COMMENT '是否显示sql脚本 1/0',
                                 `datasource_id` int DEFAULT NULL COMMENT '数据源ID',
                                 `datasource_type` varchar(50)  DEFAULT NULL COMMENT '数据源类型',
                                 `schema_name` varchar(100)  DEFAULT NULL,
                                 `table_name` varchar(256)  DEFAULT NULL COMMENT '表名',
                                 `release_time` datetime DEFAULT NULL COMMENT '发布时间',
                                 `draft_content` longtext COMMENT '已发布接口暂存内容',
                                 `plugin_config` text COMMENT '数据插件配置JSON',
                                 `remarks` varchar(1000) DEFAULT NULL COMMENT '描述',
                                 `version` varchar(100)  DEFAULT NULL COMMENT '版本',
                                 `create_by` varchar(128) DEFAULT NULL,
                                 `create_time` datetime DEFAULT NULL,
                                 `update_by` varchar(128) DEFAULT NULL,
                                 `update_time` datetime DEFAULT NULL,
                                 PRIMARY KEY (`api_id`),
                                 KEY `api_path_method_inx` (`api_path`(255),`api_method`) USING BTREE,
                                 KEY `idx_api_status_release_time` (`api_status`, `release_time` DESC),
                                 KEY `idx_group_id` (`group_id`),
                                 KEY `idx_parent_id_status` (`parent_id`, `api_status`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='API信息表';


CREATE TABLE `base_api_log` (
                                `log_id` bigint NOT NULL AUTO_INCREMENT COMMENT '日志主键',
                                `api_id` bigint DEFAULT NULL COMMENT 'api编号',
                                `api_name` varchar(255) DEFAULT NULL COMMENT 'api名称',
                                `auth_type` varchar(50) DEFAULT NULL COMMENT '认证方式',
                                `app_name` varchar(50) DEFAULT NULL COMMENT '应用名称',
                                `api_method` varchar(20) DEFAULT NULL COMMENT '请求方式',
                                `api_path` varchar(1000) DEFAULT NULL COMMENT '请求地址',
                                `request_ip` varchar(128) DEFAULT NULL COMMENT '主机地址',
                                `query_param` varchar(2000) DEFAULT NULL COMMENT '请求参数',
                                `request_body` text COMMENT '请求Body参数',
                                `response_body` longtext COMMENT '返回参数',
                                `response_code` int DEFAULT NULL COMMENT 'HTTP请求响应码',
                                `body_size` int DEFAULT NULL COMMENT '响应body大小',
                                `request_status` varchar(50) DEFAULT NULL COMMENT '操作状态',
                                `request_time` datetime DEFAULT NULL COMMENT '请求时间',
                                `response_time` datetime DEFAULT NULL COMMENT '响应时间',
                                `cost_time` int DEFAULT '0' COMMENT '消耗时间',
                                `visitor_name` varchar(50) DEFAULT NULL COMMENT '访问人员',
                                PRIMARY KEY (`log_id`),
                                KEY `idx_api_id` (`api_id`),
                                KEY `idx_request_time` (`request_time` DESC),
                                KEY `idx_app_name` (`app_name`),
                                KEY `idx_api_id_time` (`api_id`, `request_time` DESC)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='接口访问日志';

CREATE TABLE `base_app` (
                            `app_id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
                            `app_name` varchar(100)  NOT NULL COMMENT '应用名创',
                            `app_desc` varchar(300)  DEFAULT NULL COMMENT '描述',
                            `app_code` varchar(128)  DEFAULT NULL COMMENT 'code简单认证',
                            `app_key` varchar(128)  DEFAULT NULL COMMENT '密钥key',
                            `app_secret` varchar(256)  DEFAULT NULL COMMENT '密钥',
                            `strategy_type` varchar(20)  DEFAULT NULL COMMENT '控制策略类型：白名单：white、黑名单black',
                            `ips` varchar(1000) DEFAULT NULL COMMENT 'IP地址，多个分号隔开',
                            `enabled` int DEFAULT NULL COMMENT '状态：1启用，0禁用',
                            `create_by` varchar(128) DEFAULT NULL,
                            `create_time` datetime DEFAULT NULL,
                            `update_by` varchar(128) DEFAULT NULL,
                            `update_time` datetime DEFAULT NULL,
                            PRIMARY KEY (`app_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1000 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='应用';

CREATE TABLE `base_app_api` (
                                `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
                                `app_id` bigint NOT NULL COMMENT '应用ID',
                                `api_id` bigint DEFAULT NULL COMMENT 'ApiId',
                                `create_by` varchar(128) DEFAULT NULL,
                                `create_time` datetime DEFAULT NULL,
                                PRIMARY KEY (`id`),
                                KEY `idx_api_id` (`api_id`),
                                KEY `idx_app_api` (`app_id`, `api_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1000 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='应用与API授权关系';

CREATE TABLE `base_datasource` (
  `datasource_id` int NOT NULL AUTO_INCREMENT,
  `datasource_name` varchar(256) DEFAULT NULL COMMENT '数据源名称',
  `datasource_type` varchar(50) DEFAULT NULL COMMENT '数据源类型',
  `classify` varchar(50) DEFAULT NULL COMMENT '分类',
  `jdbc_url` varchar(512) DEFAULT NULL COMMENT '链接地址',
  `host` varchar(50) DEFAULT NULL COMMENT '服务器地址',
  `port` varchar(10) DEFAULT NULL COMMENT '端口',
  `username` varchar(100) DEFAULT NULL COMMENT '账号',
  `password` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '秘密',
  `remarks` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `min_idle` int DEFAULT NULL COMMENT '最小连接数',
  `max_active` int DEFAULT NULL COMMENT '最大连接数',
  `connect_timeout` int DEFAULT NULL COMMENT '连接超时时间',
  `idle_timeout` int DEFAULT NULL COMMENT '最大空闲时间',
  `max_lifetime` int DEFAULT NULL COMMENT '最大生命周期',
  `keepalive_time` int DEFAULT NULL COMMENT '保活时间',
  `extend` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '扩展配置',
  `secret_key` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '密码解密密钥',
  `create_by` varchar(128) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `update_by` varchar(128) DEFAULT NULL,
  PRIMARY KEY (`datasource_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1000 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据源配置';

-- crabc.base_group definition

CREATE TABLE `base_group` (
                              `group_id` int NOT NULL AUTO_INCREMENT COMMENT '自增主键',
                              `parent_id` int NOT NULL COMMENT '父类Id',
                              `group_name` varchar(500) NOT NULL,
                              `group_desc` varchar(1000) DEFAULT NULL COMMENT '描述',
                              `create_by` varchar(128) DEFAULT NULL,
                              `create_time` datetime DEFAULT NULL,
                              `update_by` varchar(128) DEFAULT NULL,
                              `update_time` datetime DEFAULT NULL,
                              PRIMARY KEY (`group_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1000 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分组表';


-- crabc.base_sys_user definition

CREATE TABLE `base_sys_user` (
                                 `user_id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
                                 `user_name` varchar(30) NOT NULL COMMENT '用户账号',
                                 `nick_name` varchar(30)  NOT NULL COMMENT '用户名称',
                                 `user_type` varchar(2) DEFAULT '0' COMMENT '用户类型',
                                 `password` varchar(100)  DEFAULT '' COMMENT '密码',
                                 `role` varchar(128) DEFAULT NULL COMMENT '角色',
                                 `email` varchar(50) DEFAULT '' COMMENT '用户邮箱',
                                 `phone` varchar(13) DEFAULT '' COMMENT '手机号码',
                                 `sex` char(1) DEFAULT '0' COMMENT '用户性别（0男 1女 2未知）',
                                 `picture` varchar(100)  DEFAULT '' COMMENT '头像',
                                 `status` char(1)  DEFAULT '1' COMMENT '帐号状态（0正常 1停用）',
                                 `create_by` varchar(64) DEFAULT '' COMMENT '创建者',
                                 `create_time` datetime DEFAULT NULL COMMENT '创建时间',
                                 `update_by` varchar(64) DEFAULT '' COMMENT '更新者',
                                 `update_time` datetime DEFAULT NULL COMMENT '更新时间',
                                 `remark` varchar(500) DEFAULT NULL COMMENT '备注',
                                 PRIMARY KEY (`user_id`),
                                 UNIQUE KEY `uk_user_name` (`user_name`),
                                 KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=10000 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户信息表';

-- 2.0新增表
DROP TABLE IF EXISTS `base_api_param`;
CREATE TABLE `base_api_param` (
                                  `param_id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键',
                                  `api_id` bigint NOT NULL COMMENT 'apiId',
                                  `param_name` varchar(256)  DEFAULT NULL COMMENT '参数名',
                                  `param_type` varchar(30)  DEFAULT '1' COMMENT '参数类型：1:请求参数，2:返回参数',
                                  `column_name` varchar(256)  DEFAULT NULL COMMENT '关联字段名',
                                  `param_model` varchar(30)  DEFAULT NULL COMMENT '参数模型，request,response',
                                  `required` varchar(10) DEFAULT 'Y' COMMENT '是否必填',
                                  `operation` varchar(50)  DEFAULT NULL COMMENT '操作符',
                                  `default_value` varchar(512)  DEFAULT NULL COMMENT '默认值',
                                  `example` varchar(512)  DEFAULT NULL COMMENT '示例值',
                                  `datasource_id` int DEFAULT NULL COMMENT '数据源ID',
                                  `schema_name` varchar(128)  DEFAULT NULL COMMENT 'Schema名',
                                  `table_name` varchar(256)  DEFAULT NULL COMMENT '表名',
                                  `param_desc` varchar(512)  DEFAULT NULL COMMENT '描述',
                                  `create_time` datetime DEFAULT NULL,
                                  PRIMARY KEY (`param_id`),
                                  KEY `idx_api_id` (`api_id`),
                                  KEY `idx_api_id_model` (`api_id`, `param_model`)
) ENGINE=InnoDB AUTO_INCREMENT=100 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='API接口参数';

-- 初始化数据
INSERT INTO base_sys_user
(user_id, user_name, nick_name, user_type, password, `role`, email, phone, sex, picture, status, create_by, create_time, update_by, update_time, remark)
VALUES(1, 'admin', 'admin', '0', '0192023A7BBD73250516F069DF18B500', 'admin', '', '', '1', '', '1', '1', '2023-03-01 00:00:00', '1', '2023-03-01 00:00:00', '密码：admin123');

INSERT INTO base_group
(group_id, parent_id, group_name, group_desc, create_by, create_time, update_by, update_time)
VALUES(1, 0, '默认分组', 'root', '1', '2023-03-01 00:00:00', '1', '2023-03-01 00:00:00');
