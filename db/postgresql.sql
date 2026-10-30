-- 建表
-- 1.0
CREATE TABLE base_api_info (
    api_id BIGSERIAL PRIMARY KEY,
    api_name VARCHAR(512) DEFAULT NULL,
    api_path VARCHAR(512) DEFAULT NULL,
    api_type VARCHAR(30) DEFAULT NULL,
    api_method VARCHAR(30) DEFAULT NULL,
    auth_type VARCHAR(50) DEFAULT NULL,
    enabled INTEGER DEFAULT NULL,
    api_status VARCHAR(50) DEFAULT NULL,
    group_id INTEGER DEFAULT NULL,
    parent_id BIGINT DEFAULT NULL,
    tenant_id VARCHAR(128) DEFAULT NULL,
    page_setup INTEGER DEFAULT NULL,
    transaction_enabled INTEGER DEFAULT 0,
    rate_limit_window_seconds INTEGER DEFAULT NULL,
    rate_limit_count INTEGER DEFAULT NULL,
    script_timeout_millis INTEGER DEFAULT 0,
    sql_type VARCHAR(20) DEFAULT NULL,
    result_type VARCHAR(30) DEFAULT NULL,
    sql_script TEXT,
    show_sql_script INTEGER DEFAULT NULL,
    datasource_id INTEGER DEFAULT NULL,
    datasource_type VARCHAR(50) DEFAULT NULL,
    schema_name VARCHAR(100) DEFAULT NULL,
    table_name VARCHAR(256) DEFAULT NULL,
    release_time TIMESTAMP DEFAULT NULL,
    draft_content TEXT,
    plugin_config TEXT,
    remarks VARCHAR(1000) DEFAULT NULL,
    version VARCHAR(100) DEFAULT NULL,
    create_by VARCHAR(128) DEFAULT NULL,
    create_time TIMESTAMP DEFAULT NULL,
    update_by VARCHAR(128) DEFAULT NULL,
    update_time TIMESTAMP DEFAULT NULL
);

COMMENT ON TABLE base_api_info IS 'API信息表';
COMMENT ON COLUMN base_api_info.api_id IS '自增主键';
COMMENT ON COLUMN base_api_info.api_path IS 'API路径';
COMMENT ON COLUMN base_api_info.api_type IS 'API类型：sql、table';
COMMENT ON COLUMN base_api_info.api_method IS '请求方式 get、post、put、delete、patch';
COMMENT ON COLUMN base_api_info.auth_type IS '授权类型：none、code、secret';
COMMENT ON COLUMN base_api_info.enabled IS '开放启用 1/0';
COMMENT ON COLUMN base_api_info.api_status IS 'API状态：编辑edit、审批audit、发布release、销毁destroy';
COMMENT ON COLUMN base_api_info.group_id IS '分组ID';
COMMENT ON COLUMN base_api_info.parent_id IS '父级关联Id';
COMMENT ON COLUMN base_api_info.tenant_id IS '租户ID';
COMMENT ON COLUMN base_api_info.page_setup IS '分页设置，不分页：0, 分页：1';
COMMENT ON COLUMN base_api_info.transaction_enabled IS '是否开启事务 1/0';
COMMENT ON COLUMN base_api_info.rate_limit_window_seconds IS '限流时间窗口秒数';
COMMENT ON COLUMN base_api_info.rate_limit_count IS '限流窗口请求次数';
COMMENT ON COLUMN base_api_info.script_timeout_millis IS '脚本超时时间（毫秒），0或空使用默认30000毫秒';
COMMENT ON COLUMN base_api_info.sql_type IS 'SQL执行类型（select、insert、update、delete）';
COMMENT ON COLUMN base_api_info.result_type IS '返回结果类型： one、array、excel';
COMMENT ON COLUMN base_api_info.sql_script IS 'SQL脚本';
COMMENT ON COLUMN base_api_info.show_sql_script IS '是否显示sql脚本 1/0';
COMMENT ON COLUMN base_api_info.datasource_id IS '数据源ID';
COMMENT ON COLUMN base_api_info.datasource_type IS '数据源类型';
COMMENT ON COLUMN base_api_info.table_name IS '表名';
COMMENT ON COLUMN base_api_info.release_time IS '发布时间';
COMMENT ON COLUMN base_api_info.draft_content IS '已发布接口暂存内容';
COMMENT ON COLUMN base_api_info.plugin_config IS '数据插件配置JSON';
COMMENT ON COLUMN base_api_info.remarks IS '描述';
COMMENT ON COLUMN base_api_info.version IS '版本';

-- 设置序列起始值
ALTER SEQUENCE base_api_info_api_id_seq RESTART WITH 10000;

-- 创建索引
CREATE INDEX api_path_method_inx ON base_api_info(api_path, api_method);
CREATE INDEX idx_api_status_release_time ON base_api_info(api_status, release_time DESC);
CREATE INDEX idx_group_id ON base_api_info(group_id);
CREATE INDEX idx_parent_id_status ON base_api_info(parent_id, api_status);


CREATE TABLE base_api_log (
    log_id BIGSERIAL PRIMARY KEY,
    api_id BIGINT DEFAULT NULL,
    api_name VARCHAR(255) DEFAULT NULL,
    auth_type VARCHAR(50) DEFAULT NULL,
    app_name VARCHAR(50) DEFAULT NULL,
    api_method VARCHAR(20) DEFAULT NULL,
    api_path VARCHAR(1000) DEFAULT NULL,
    request_ip VARCHAR(128) DEFAULT NULL,
    query_param VARCHAR(2000) DEFAULT NULL,
    request_body TEXT,
    response_body TEXT,
    response_code INTEGER DEFAULT NULL,
    body_size INTEGER DEFAULT NULL,
    request_status VARCHAR(50) DEFAULT NULL,
    request_time TIMESTAMP DEFAULT NULL,
    response_time TIMESTAMP DEFAULT NULL,
    cost_time INTEGER DEFAULT 0
);

COMMENT ON TABLE base_api_log IS '接口访问日志';
COMMENT ON COLUMN base_api_log.log_id IS '日志主键';
COMMENT ON COLUMN base_api_log.api_id IS 'api编号';
COMMENT ON COLUMN base_api_log.api_name IS 'api名称';
COMMENT ON COLUMN base_api_log.auth_type IS '认证方式';
COMMENT ON COLUMN base_api_log.app_name IS '应用名称';
COMMENT ON COLUMN base_api_log.api_method IS '请求方式';
COMMENT ON COLUMN base_api_log.api_path IS '请求地址';
COMMENT ON COLUMN base_api_log.request_ip IS '主机地址';
COMMENT ON COLUMN base_api_log.query_param IS '请求参数';
COMMENT ON COLUMN base_api_log.request_body IS '请求Body参数';
COMMENT ON COLUMN base_api_log.response_body IS '返回参数';
COMMENT ON COLUMN base_api_log.response_code IS 'HTTP请求响应码';
COMMENT ON COLUMN base_api_log.body_size IS '响应body大小';
COMMENT ON COLUMN base_api_log.request_status IS '操作状态';
COMMENT ON COLUMN base_api_log.request_time IS '请求时间';
COMMENT ON COLUMN base_api_log.response_time IS '响应时间';
COMMENT ON COLUMN base_api_log.cost_time IS '消耗时间';

-- 设置序列起始值
ALTER SEQUENCE base_api_log_log_id_seq RESTART WITH 100;

-- 创建索引
CREATE INDEX idx_api_id ON base_api_log(api_id);
CREATE INDEX idx_request_time ON base_api_log(request_time DESC);
CREATE INDEX idx_api_id_time ON base_api_log(api_id, request_time DESC);


CREATE TABLE base_app (
    app_id BIGSERIAL PRIMARY KEY,
    app_name VARCHAR(100) NOT NULL,
    app_desc VARCHAR(300) DEFAULT NULL,
    app_code VARCHAR(128) DEFAULT NULL,
    app_key VARCHAR(128) DEFAULT NULL,
    app_secret VARCHAR(256) DEFAULT NULL,
    strategy_type VARCHAR(20) DEFAULT NULL,
    ips VARCHAR(1000) DEFAULT NULL,
    enabled INTEGER DEFAULT NULL,
    create_by VARCHAR(128) DEFAULT NULL,
    create_time TIMESTAMP DEFAULT NULL,
    update_by VARCHAR(128) DEFAULT NULL,
    update_time TIMESTAMP DEFAULT NULL
);

COMMENT ON TABLE base_app IS '应用';
COMMENT ON COLUMN base_app.app_id IS '自增主键';
COMMENT ON COLUMN base_app.app_name IS '应用名称';
COMMENT ON COLUMN base_app.app_desc IS '描述';
COMMENT ON COLUMN base_app.app_code IS 'code简单认证';
COMMENT ON COLUMN base_app.app_key IS '密钥key';
COMMENT ON COLUMN base_app.app_secret IS '密钥';
COMMENT ON COLUMN base_app.strategy_type IS '控制策略类型：白名单：white、黑名单black';
COMMENT ON COLUMN base_app.ips IS 'IP地址，多个分号隔开';
COMMENT ON COLUMN base_app.enabled IS '状态：1启用，0禁用';

-- 设置序列起始值
ALTER SEQUENCE base_app_app_id_seq RESTART WITH 1000;


CREATE TABLE base_app_api (
    id BIGSERIAL PRIMARY KEY,
    app_id BIGINT NOT NULL,
    api_id BIGINT DEFAULT NULL,
    create_by VARCHAR(128) DEFAULT NULL,
    create_time TIMESTAMP DEFAULT NULL
);

COMMENT ON TABLE base_app_api IS '应用与API授权关系';
COMMENT ON COLUMN base_app_api.id IS '自增主键';
COMMENT ON COLUMN base_app_api.app_id IS '应用ID';
COMMENT ON COLUMN base_app_api.api_id IS 'ApiId';

-- 设置序列起始值
ALTER SEQUENCE base_app_api_id_seq RESTART WITH 1000;

-- 创建索引
CREATE INDEX idx_api_id ON base_app_api(api_id);
CREATE INDEX idx_app_api ON base_app_api(app_id, api_id);


CREATE TABLE base_datasource (
    datasource_id SERIAL PRIMARY KEY,
    datasource_name VARCHAR(256) DEFAULT NULL,
    datasource_type VARCHAR(50) DEFAULT NULL,
    classify VARCHAR(50) DEFAULT NULL,
    jdbc_url VARCHAR(512) DEFAULT NULL,
    host VARCHAR(50) DEFAULT NULL,
    port VARCHAR(10) DEFAULT NULL,
    username VARCHAR(100) DEFAULT NULL,
    password VARCHAR(256) DEFAULT NULL,
    remarks VARCHAR(500) DEFAULT NULL,
    min_idle INTEGER DEFAULT NULL,
    max_active INTEGER DEFAULT NULL,
    connect_timeout INTEGER DEFAULT NULL,
    idle_timeout INTEGER DEFAULT NULL,
    max_lifetime INTEGER DEFAULT NULL,
    keepalive_time INTEGER DEFAULT NULL,
    extend VARCHAR(1000) DEFAULT NULL,
    secret_key TEXT,
    create_by VARCHAR(128) DEFAULT NULL,
    create_time TIMESTAMP DEFAULT NULL,
    update_time TIMESTAMP DEFAULT NULL,
    update_by VARCHAR(128) DEFAULT NULL
);

COMMENT ON TABLE base_datasource IS '数据源配置';
COMMENT ON COLUMN base_datasource.datasource_name IS '数据源名称';
COMMENT ON COLUMN base_datasource.datasource_type IS '数据源类型';
COMMENT ON COLUMN base_datasource.classify IS '分类';
COMMENT ON COLUMN base_datasource.jdbc_url IS '链接地址';
COMMENT ON COLUMN base_datasource.host IS '服务器地址';
COMMENT ON COLUMN base_datasource.port IS '端口';
COMMENT ON COLUMN base_datasource.username IS '账号';
COMMENT ON COLUMN base_datasource.password IS '密码';
COMMENT ON COLUMN base_datasource.min_idle IS '最小连接数';
COMMENT ON COLUMN base_datasource.max_active IS '最大连接数';
COMMENT ON COLUMN base_datasource.connect_timeout IS '连接超时时间';
COMMENT ON COLUMN base_datasource.idle_timeout IS '最大空闲时间';
COMMENT ON COLUMN base_datasource.max_lifetime IS '最大生命周期';
COMMENT ON COLUMN base_datasource.keepalive_time IS '保活时间';
COMMENT ON COLUMN base_datasource.extend IS '扩展配置';
COMMENT ON COLUMN base_datasource.secret_key IS '密码解密密钥';

-- 设置序列起始值
ALTER SEQUENCE base_datasource_datasource_id_seq RESTART WITH 1000;


CREATE TABLE base_group (
    group_id SERIAL PRIMARY KEY,
    parent_id INTEGER NOT NULL,
    group_name VARCHAR(500) NOT NULL,
    group_desc VARCHAR(1000) DEFAULT NULL,
    create_by VARCHAR(128) DEFAULT NULL,
    create_time TIMESTAMP DEFAULT NULL,
    update_by VARCHAR(128) DEFAULT NULL,
    update_time TIMESTAMP DEFAULT NULL
);

COMMENT ON TABLE base_group IS '分组表';
COMMENT ON COLUMN base_group.group_id IS '自增主键';
COMMENT ON COLUMN base_group.parent_id IS '父类Id';
COMMENT ON COLUMN base_group.group_desc IS '描述';

-- 设置序列起始值
ALTER SEQUENCE base_group_group_id_seq RESTART WITH 1000;


CREATE TABLE base_sys_user (
    user_id BIGSERIAL PRIMARY KEY,
    user_name VARCHAR(30) NOT NULL,
    nick_name VARCHAR(30) NOT NULL,
    user_type VARCHAR(2) DEFAULT '0',
    password VARCHAR(100) DEFAULT '',
    role VARCHAR(128) DEFAULT NULL,
    email VARCHAR(50) DEFAULT '',
    phone VARCHAR(13) DEFAULT '',
    sex CHAR(1) DEFAULT '0',
    picture VARCHAR(100) DEFAULT '',
    status CHAR(1) DEFAULT '1',
    create_by VARCHAR(64) DEFAULT '',
    create_time TIMESTAMP DEFAULT NULL,
    update_by VARCHAR(64) DEFAULT '',
    update_time TIMESTAMP DEFAULT NULL,
    remark VARCHAR(500) DEFAULT NULL
);

COMMENT ON TABLE base_sys_user IS '用户信息表';
COMMENT ON COLUMN base_sys_user.user_id IS '用户ID';
COMMENT ON COLUMN base_sys_user.user_name IS '用户账号';
COMMENT ON COLUMN base_sys_user.nick_name IS '用户名称';
COMMENT ON COLUMN base_sys_user.user_type IS '用户类型';
COMMENT ON COLUMN base_sys_user.password IS '密码';
COMMENT ON COLUMN base_sys_user.role IS '角色';
COMMENT ON COLUMN base_sys_user.email IS '用户邮箱';
COMMENT ON COLUMN base_sys_user.phone IS '手机号码';
COMMENT ON COLUMN base_sys_user.sex IS '用户性别（0男 1女 2未知）';
COMMENT ON COLUMN base_sys_user.picture IS '头像';
COMMENT ON COLUMN base_sys_user.status IS '帐号状态（0正常 1停用）';
COMMENT ON COLUMN base_sys_user.create_by IS '创建者';
COMMENT ON COLUMN base_sys_user.create_time IS '创建时间';
COMMENT ON COLUMN base_sys_user.update_by IS '更新者';
COMMENT ON COLUMN base_sys_user.update_time IS '更新时间';
COMMENT ON COLUMN base_sys_user.remark IS '备注';

-- 设置序列起始值
ALTER SEQUENCE base_sys_user_user_id_seq RESTART WITH 10000;

-- 创建索引
CREATE UNIQUE INDEX uk_user_name ON base_sys_user(user_name);
CREATE INDEX idx_status ON base_sys_user(status);

-- 2.0新增表
DROP TABLE IF EXISTS base_api_param;
CREATE TABLE base_api_param (
    param_id BIGSERIAL PRIMARY KEY,
    api_id BIGINT NOT NULL,
    param_name VARCHAR(256) DEFAULT NULL,
    param_type VARCHAR(30) DEFAULT '1',
    column_name VARCHAR(256) DEFAULT NULL,
    param_model VARCHAR(30) DEFAULT NULL,
    required VARCHAR(10) DEFAULT 'Y',
    operation VARCHAR(50) DEFAULT NULL,
    default_value VARCHAR(512) DEFAULT NULL,
    example VARCHAR(512) DEFAULT NULL,
    datasource_id INTEGER DEFAULT NULL,
    schema_name VARCHAR(128) DEFAULT NULL,
    table_name VARCHAR(256) DEFAULT NULL,
    param_desc VARCHAR(512) DEFAULT NULL,
    create_time TIMESTAMP DEFAULT NULL
);

COMMENT ON TABLE base_api_param IS 'API接口参数';
COMMENT ON COLUMN base_api_param.param_id IS '自增主键';
COMMENT ON COLUMN base_api_param.api_id IS 'apiId';
COMMENT ON COLUMN base_api_param.param_name IS '参数名';
COMMENT ON COLUMN base_api_param.param_type IS '参数类型：1:请求参数，2:返回参数';
COMMENT ON COLUMN base_api_param.column_name IS '关联字段名';
COMMENT ON COLUMN base_api_param.param_model IS '参数模型，request,response';
COMMENT ON COLUMN base_api_param.required IS '是否必填';
COMMENT ON COLUMN base_api_param.operation IS '操作符';
COMMENT ON COLUMN base_api_param.default_value IS '默认值';
COMMENT ON COLUMN base_api_param.example IS '示例值';
COMMENT ON COLUMN base_api_param.datasource_id IS '数据源ID';
COMMENT ON COLUMN base_api_param.schema_name IS 'Schema名';
COMMENT ON COLUMN base_api_param.table_name IS '表名';
COMMENT ON COLUMN base_api_param.param_desc IS '描述';

-- 设置序列起始值
ALTER SEQUENCE base_api_param_param_id_seq RESTART WITH 100;

-- 创建索引
CREATE INDEX idx_api_id ON base_api_param(api_id);
CREATE INDEX idx_api_id_model ON base_api_param(api_id, param_model);

-- 初始化数据
INSERT INTO base_sys_user
(user_id, user_name, nick_name, user_type, password, role, email, phone, sex, picture, status, create_by, create_time, update_by, update_time, remark)
VALUES(1, 'admin', 'admin', '0', '0192023A7BBD73250516F069DF18B500', 'admin', '', '', '1', '', '1', '1', '2023-03-01 00:00:00', '1', '2023-03-01 00:00:00', '密码：admin123');

INSERT INTO base_group
(group_id, parent_id, group_name, group_desc, create_by, create_time, update_by, update_time)
VALUES(1, 0, '默认分组', 'root', '1', '2023-03-01 00:00:00', '1', '2023-03-01 00:00:00');
