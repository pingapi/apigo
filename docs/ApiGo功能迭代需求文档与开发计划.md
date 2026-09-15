# ApiGo 接口平台功能迭代需求文档与开发计划

> 版本：v1.0 ｜ 编写日期：2026-09-06 ｜ 适用分支：`dev`（Spring Boot 4.1.0 / JDK 21）
> 依据：对仓库源码的全量分析（crabc-api 后端四模块、crabc-web 前端、db 建库脚本），所有现状结论均附代码依据。

---

## 一、平台现状分析

### 1.1 平台定位

ApiGo 是一款开源 SQL-to-API 接口开发平台：在线可视化编写 SQL，快速发布为 REST API 对外提供服务，支持多数据源接入、动态 SQL（MyBatis 标签语法）、应用凭证认证（AppKey / 国密 SM3 签名）、限流、调用日志与监控统计。

### 1.2 整体架构（现状）

```
┌────────────────────── 控制台前端 crabc-web（Vue3，内嵌于 9377 或独立 nginx）──────────────────────┐
│  工作台(SQL开发) │ 数据源 │ 接口列表 │ 调用凭证 │ 监控统计 │ 调用日志 │ 接口文档(/doc 公开)      │
└───────────────────────────────────┬──────────────────────────────────────────────────────────┘
                     控制面 API：/api/box/sys/**（JWT 鉴权，JwtInterceptor）
┌───────────────────────────────────▼──────────────────────────────────────────────────────────┐
│  crabc-admin（启动模块，AdminApplication，端口 9377）                                          │
│  crabc-core（管理面）：ApiInfo/DataSource/App/Group/User/Log/MetaData/Test 共 9 个 Controller │
│    · 接口生命周期：保存 → 发布(api_status=release) → 上线/下线(state) → 销毁(destroy)          │
│    · 已发布接口支持草稿暂存(draft_content)、历史版本存档(parent_id/version)                    │
├───────────────────────────────────────────────────────────────────────────────────────────────┤
│  数据面网关 /api/web/**（对外开放的已发布 API）                                                 │
│    AuthInterceptor：API配置缓存 → 限流(Bucket4j令牌桶) → 认证(none/app_code/app_key/SM3签名)   │
│    ApiServiceController：参数安全校验(数量/长度/XSS) → IBaseDataService.execute                │
│    访问日志：有界队列(2000) → 守护线程批量落库 base_api_log                                     │
├───────────────────────────────────────────────────────────────────────────────────────────────┤
│  crabc-datasource（动态数据源）                                                                │
│    HikariCP 连接池 + AbstractRoutingDataSource 路由(ThreadLocal: datasourceId:type:schema)    │
│    内置驱动：MySQL/PostgreSQL/Oracle/SQLServer/达梦/ClickHouse/DuckDB/DolphinDB/H2 等          │
├───────────────────────────────────────────────────────────────────────────────────────────────┤
│  crabc-spi（插件扩展点）：DataSourceDriver / StatementMapper / MetaDataMapper / bean           │
└───────────────────────────────────────────────────────────────────────────────────────────────┘
```

**双前缀设计**：控制面 `/api/box/sys/**` 走登录 JWT；数据面 `/api/web/{apiPath}` 走应用级认证（AppCode / AppKey / SM3 签名），appKey 不在 URL 路径中。

### 1.3 技术栈

| 层次 | 选型 | 备注 |
|---|---|---|
| 运行时 | JDK 21 + 虚拟线程（`spring.threads.virtual.enabled`） | `jdk-8` 分支为 Boot 2.7 + JDK 8 |
| 框架 | Spring Boot 4.1.0（Jakarta 命名空间） | |
| ORM | MyBatis 4.0.1 + PageHelper 4.1.0（多方言分页） | 未实际使用 MyBatis-Plus |
| 连接池 | 平台库 Druid / 业务数据源 HikariCP | |
| 缓存 | Caffeine（dataCache 15min / apiCache 30min） | **无 Redis 依赖** |
| 限流 | Bucket4j 令牌桶（Caffeine 存储，单机） | 不支持集群 |
| 认证 | jjwt 0.13.0（HS256，密钥硬编码，8h 过期）+ BouncyCastle SM3 | |
| 动态 SQL | SQLUtil 分句（引号/注释/CDATA/标签感知）→ MyBatis `<script>` LanguageDriver 渲染 → PreparedStatement 绑定 | 复用 `#{}`/if/foreach 动态标签 |
| 执行防护 | 结果 ≤10000 行、超时 30s、批量 ≤1000 条、危险 SQL 黑名单 | `JdbcStatement.validateSqlSafety` |
| 前端 | Vue 3.5 + Vite 7 + ant-design-vue 4 + vuex 4 + CodeMirror 6 + @antv/g2，仅中文 | 构建产物手工拷入 `crabc-core/src/main/resources/static` |
| 数据模型 | 8 张 `base_*` 表（api_info/api_log/app/app_api/datasource/group/sys_user/api_param） | MySQL + PostgreSQL 双方言脚本 |

### 1.4 功能实现现状盘点（README 宣传 vs 代码实际）

| 功能 | 代码现状 | 依据 |
|---|---|---|
| 工作台 SQL→API | ✅ 完整（CodeMirror6 + sqlParse 解析参数 + 运行预览 + 测试 + 发布） | `TabSql.vue`、`ApiTestController` |
| 多数据源管理 | ✅ 完整（10+ 数据库类型、连接池参数、热注册、5 分钟快照比对重建） | `BaseDataSourceServiceImpl` |
| 应用凭证与授权 | ✅ AppCode/AppKey/SM3 签名认证 + 应用↔API 授权 | `AuthInterceptor.checkSM3` |
| 接口上下线/限流 | ✅ 上下线 + 接口级令牌桶限流配置 | `ApiInfoController`、`ApiRateLimitService` |
| 监控统计/调用日志 | ✅ 6 类统计接口 + 日志落库查询（异步队列） | `ApiLogController`、`AuthInterceptor.afterCompletion` |
| 多 SQL 事务 | ✅ 多脚本 DML 单连接事务执行 | `BaseDataServiceImpl.executeBatchDml` |
| 接口文档 | ⚠️ 弱实现（详情+参数+在线测试页，无 OpenAPI/Swagger 导出） | `CommonController` |
| 数据脱敏 | ❌ 未实现（全库无脱敏代码） | — |
| 数据转换 | ❌ 未实现 | — |
| 国密加密 | ⚠️ 仅 SM3 签名；SM2/SM4 加解密未实现 | `SM3Util.java` |
| 接口编排 | ❌ 未实现（`doc/flow.png` 仅为宣传图） | — |
| 审批流 | ❌ 未实现（`ApiStateEnum.AUDIT` 状态与前端 `/sys/audit/*` 接口定义为遗留，发布即时生效） | `BaseApiInfoServiceImpl.apiPublish` |
| 告警 | ❌ 未实现 | — |
| Mock | ❌ 未实现 | — |

### 1.5 现状短板与迭代机会

1. **安全短板（生产可用阻断项）**
   - JWT 密钥硬编码（`JwtUtil.java`）、登录密码 MD5 无盐存储、数据源密码仅 Base64 存库（`secret_key` 字段预留未用）。
   - IP 黑白名单：`base_app.strategy_type/ips` 字段存在，代码未消费。
   - 无管理员操作审计：接口/数据源/凭证的任何变更无操作记录。
   - 无按钮级权限（前端 `$auth` 为模板遗留），`base_sys_user.role` 单字段，租户 `tenant_id` 字段未启用。
2. **集群化缺失**：限流（Caffeine）、API 配置缓存、访问日志队列、登录态全部单机内存态，多实例部署即失效。
3. **API 生命周期不完整**：版本仅存档无对比/回滚 UI；无 OpenAPI 导入导出；无 Mock；发布即生效无审批闸门。
4. **README 宣传功能未落地**：脱敏、转换、编排、告警为空白（对外口径与代码不符，也是社区用户高频期待）。
5. **工程质量**：后端无任何测试代码；前端构建到后端 static 为手工拷贝；`db/mysql.sql` 建库 `apigo` 与 `application.yml` 默认库 `crabc` 不一致；前端遗留 `/sys/audit/*`、`/sys/message/*` 等无后端支撑的接口定义。

---

## 二、迭代方向与版本目标

两条主线并行，按"先底座、后智能"推进：

| 主线 | 目标 | 对应版本 |
|---|---|---|
| **主线一：底座补强** | 补齐生产可用能力：安全加固、审批流、操作审计、集群化、版本回滚、OpenAPI、脱敏、告警 | v5.6.0（P0）→ v6.0.0（P1） |
| **主线二：AI 能力** | 内置 MCP Server，让 workbuddy、千问办公等 AI 办公平台通过 MCP 连接平台，AI 对话直接完成"建数据源→写 SQL→建接口→发布→授权→查日志"全业务流程 | v6.0.0（核心卖点） |

**两主线的耦合点**：AI 的写操作（发布/删除）复用底座的**审批流**作为安全闸门，复用**操作审计**做 AI 行为留痕——底座补强是 AI 能力安全落地的前置条件。

---

## 三、功能需求（一）：平台底座补强

> 需求编号 BASE-xx；优先级 P0 = 生产可用必须，P1 = 竞争力增强，P2 = 远期。

### BASE-01 发布审批流（P0，12 人日）

- **背景**：`api_status` 已定义 `audit` 状态、前端遗留 `/sys/audit/*` 接口定义，但发布即时生效（`BaseApiInfoServiceImpl.apiPublish` 直接置 `release`）。生产环境中接口发布需要审核闸门，同时也是 AI 写操作的安全闭环（见 AI-03）。
- **需求描述**：发布接口可选进入审批流程（平台级开关 `crabc.audit.enabled`，默认关闭保持现行为）；审批通过后自动发布并上线。
- **功能点**：
  1. 新表 `base_api_audit`：`id, api_id, api_snapshot(JSON), apply_type(publish/destroy/offline), apply_reason, status(pending/approved/rejected), approver, approve_time, approve_comment`；
  2. 发布/销毁接口时若审批开启：`api_status=audit`，生成审批单，接口变更内容存快照；
  3. 审批中心页面：待审/已审列表、审批详情（对比草稿与线上版本的 SQL diff）、通过/驳回；
  4. 通过后系统自动执行发布动作（复用 `apiPublish` 逻辑）；驳回则接口回到 edit；
  5. 消息通知（站内信落 `base_sys_message`，可选 webhook）。
- **接口设计**：`POST /sys/audit/apply`、`GET /sys/audit/page`、`GET /sys/audit/{id}`、`POST /sys/audit/approve`、`POST /sys/audit/reject`、`POST /sys/audit/revoke`（与前端遗留定义对齐）。
- **验收标准**：开关关闭时行为与现状一致；开启时未审批接口不可发布上线；审批通过后接口自动发布；审批全过程可追溯。

### BASE-02 管理端操作审计日志（P0，8 人日）

- **背景**：控制面所有写操作（改 SQL、改数据源、删凭证）无任何留痕，无法回答"谁在什么时候改了什么"。
- **需求描述**：对控制面写操作全量记录审计日志，支持查询；为 AI 操作预留来源标记（`source=console|openapi|mcp`，见 AI-03）。
- **功能点**：
  1. 新表 `base_sys_audit_log`：`id, user_id, user_name, source, module(api/datasource/app/group/user/token), action(create/update/delete/publish/...), target_id, target_name, request_params(JSON), result(success/fail), client_ip, agent_session_id, cost_ms, create_time`；
  2. 实现方式：自定义注解 `@AuditLog(module, action)` + AOP 切面，逐个 Controller 写操作标注（约 30 处）；
  3. 异步写入（复用现有队列批量落库模式）；
  4. 前端"审计日志"页面（管理侧菜单，按用户/模块/时间筛选）。
- **验收标准**：任意管理端写操作均产生审计记录；AI/MCP 来源的操作可区分；审计日志不可通过业务接口修改删除。

### BASE-03 安全加固（P0，10 人日）

| 项 | 现状 | 方案 |
|---|---|---|
| JWT 密钥 | 硬编码（`JwtUtil.java`） | 密钥外置配置 + 启动校验；支持双密钥轮换窗口 |
| 用户密码 | MD5 大写无盐 | 迁移 BCrypt（首次登录自动升级旧 MD5）；登录失败 5 次锁定 10 分钟（Caffeine/Redis 计数） |
| 数据源密码 | Base64 明文可逆 | 落地 `secret_key`：AES-GCM 加密存库，密钥从环境变量注入；存量数据启动时自动迁移 |
| IP 黑白名单 | 字段存在未消费 | `AuthInterceptor` 认证前校验 `strategy_type + ips`（CIDR 支持） |
| 会话注销 | JWT 无黑名单 | 登出后 token 加入黑名单（剩余有效期） |

- **验收标准**：`admin/admin123` 弱口令登录强制改密；抓包/脱库不可还原数据源密码；黑白名单生效有单测覆盖。

### BASE-04 集群化支撑（P0，10 人日）

- **背景**：多实例部署时限流形同虚设（每实例独立令牌桶）、日志队列各自为政、API 配置缓存不一致（5 分钟定时刷新延迟）。
- **需求描述**：引入可选 Redis 依赖（`spring-boot-starter-data-redis`），通过配置 `crabc.cluster.mode=single|redis` 切换，**单机模式零依赖保持现状**。
- **功能点**：
  1. 限流存储抽象 `RateLimitStore` 接口：Caffeine 实现（现状）/ Redis Lua 令牌桶实现；
  2. API 配置缓存：Redis pub/sub 广播配置变更失效事件，替代 5 分钟轮询；
  3. 访问日志队列：单机内存队列保留，Redis 模式下按实例分片写入（或直接同步批量写库）；
  4. JWT 黑名单/登录锁定共享化。
- **验收标准**：双实例部署下限流总量精确；一实例修改接口配置，另一实例 ≤1s 生效；不启用 Redis 时行为与现状完全一致。

### BASE-05 接口版本管理与回滚（P0，6 人日）

- **背景**：`parent_id/version/releaseHistory/draft_content` 基础已有，但无版本列表/对比/回滚能力，误发布只能手工改回。
- **需求描述**：已发布接口保留全部历史版本；支持任意两版本 SQL/参数 diff 与一键回滚（回滚=以历史快照发布新版本）。
- **功能点**：版本列表抽屉（工作台 + 接口列表）、版本 diff 视图（前端 diff 组件）、`POST /sys/api/info/rollback`；历史版本存档表结构复用 `parent_id` 关联。
- **验收标准**：连续发布 3 个版本后可回滚到第 1 版且当前线上行为与第 1 版一致；回滚动作记入审计日志。

### BASE-06 OpenAPI 导入导出（P1，8 人日）

- **需求描述**：① 导出：单接口/分组/全量导出 OpenAPI 3.0 JSON 与 Markdown 文档（复用 `base_api_param` 请求/返回参数定义）；② 导入：解析 OpenAPI 3.0/Swagger 2.0 生成接口骨架（路径/方法/参数），SQL 由开发者补写；③ 分组导出 zip（含数据源脱敏引用）。
- **验收标准**：导出的 OpenAPI 文件可在 Apifox/Swagger UI 正常渲染；导入生成接口后仅需补 SQL 即可发布。

### BASE-07 数据脱敏与结果转换（P1，10 人日）

- **背景**：README 宣传能力，数据服务平台的合规刚需（手机号/身份证/银行卡出接口必脱敏）。
- **需求描述**：接口级结果集后处理规则引擎，对返回 JSON 字段按规则脱敏/转换。
- **功能点**：
  1. 新表 `base_api_mask`：`api_id, field_path, algorithm(mask/hash/replace/range/null), rule_param`；
  2. 算法：遮盖（`138****5678`）、SM3/SHA 哈希、置空、区间泛化、自定义正则替换；
  3. 执行位置：`BaseDataServiceImpl` 结果集组装后、序列化前（树遍历按 `field_path` 命中）；
  4. 工作台配置 UI（表格字段下拉 + 算法选择 + 预览效果）；
  5. 简单转换：日期格式化、枚举映射（`status:0→禁用,1→启用`）。
- **验收标准**：脱敏对分页/one/array/excel 四种 resultType 均生效；配置变更即时生效（缓存失效广播）。

### BASE-08 告警通知（P1，8 人日）

- **需求描述**：基于现有日志统计能力（`/sys/api/log/summary` 等已聚合）增加阈值告警：接口失败率、P95 耗时、调用量突增/骤降；通知渠道 webhook（钉钉/飞书/企微通用）、邮件。
- **功能点**：告警规则表 `base_alert_rule`（api 维度/全局维度、阈值、静默窗口、渠道）、定时扫描（复用 `@Scheduled`，Redis 模式下分布式锁防重）、告警记录与恢复通知、平台级 webhook 配置。
- **验收标准**：制造 50% 失败率后 5 分钟内收到 webhook 告警，恢复后收到恢复通知。

### BASE-09 工程质量与交付链（P0 底线，8 人日）

1. 后端测试体系：核心模块补关键单测（SQLUtil 分句、SM3 签名、AuthInterceptor 鉴权矩阵、BaseDataServiceImpl 执行分发、脱敏规则）+ Testcontainers 集成测试（MySQL/PG）；
2. 前端构建自动化：`frontend-maven-plugin`（或 CI 脚本）`pnpm build` → 产物自动进 `crabc-core/src/main/resources/static`，消除手工拷贝；
3. 修正 README（`db/dml.sql`→实际脚本、功能描述与社区版对齐）、统一建库脚本与默认配置库名；
4. 清理遗留：前端 `/sys/message/*`、`/sys/audit/*` 旧定义与 BASE-01 对齐或删除。

### 底座远期项（P2，不在本计划排期）

RBAC 多角色与按钮级权限、多租户启用（`tenant_id`）、接口编排引擎（多 SQL 编排/流程编排，工作量大、建议独立专项）、Mock 服务、协同开发（团队/项目空间）、ES/MongoDB 等 NoSQL 数据源插件（SPI 扩展点已具备）。

---

## 四、功能需求（二）：AI 能力（MCP 接入）

### 4.0 目标场景

> 用户在 workbuddy / 千问办公中对话：
> **"连一下测试库 192.168.1.10 的 orders 库，写个接口查最近 7 天每日订单量，发布到『订单』分组，认证用 AppKey。"**
> AI 通过 MCP 依次调用平台工具：创建数据源 → 测试连接 → 读取表结构 → 试跑 SQL → 创建接口 → 发布（自动进入审批，审批通过自动上线）→ 回复"已提交审批"。

平台以 **MCP Server** 身份内嵌（与主服务同进程部署，无需独立组件），对外提供标准 MCP 协议端点；AI 办公平台作为 MCP Client 接入。

```
┌──────────────┐   MCP(Streamable HTTP/SSE, Bearer Token)   ┌──────────────────────────────┐
│  workbuddy   │ ─────────────────────────────────────────▶ │  ApiGo MCP Server（9377/mcp） │
│  千问办公     │ ◀───────────── JSON-RPC 2.0 ────────────── │   tools: 20+ 平台业务工具      │
│  钉钉AI/Dify  │                                            │   复用管理面 Service 与校验    │
│  Claude/Cursor(stdio)                                     │   → 审批闸门(BASE-01)         │
└──────────────┘                                            │   → 操作审计(BASE-02)         │
                                                            └──────────────────────────────┘
```

### AI-01 MCP Server 基座（P0，10 人日）

- **协议与传输**：
  - **Streamable HTTP**（MCP 2025-03-26+ 规范）：`POST /mcp`（JSON-RPC 请求/响应）+ `GET /mcp`（SSE 通知流），支持 `Mcp-Session-Id` 会话管理——面向 workbuddy/千问办公等云端平台；
  - **SSE 兼容传输**（2024-11-05 规范的 `GET /sse` + `POST /messages`）：兼容仍用旧版 transport 的客户端；
  - **stdio**：提供可执行启动器（嵌入模式），供 Claude Desktop / Cursor 等本地工具使用。
- **技术选型与预案**：优先评估 Spring AI 的 `mcp-server-webmvc` starter 与 Spring Boot 4.1 的兼容性；若不兼容则**自研轻量实现**——MCP 核心仅为 JSON-RPC 2.0 上的 `initialize` / `tools/list` / `tools/call` / `ping` 四类方法（resources/prompts 本期不实现），零重依赖、预估 3~5 人日，风险可控（此为推荐主路径，可控性最高）。
- **鉴权**：`Authorization: Bearer <token>`（MCP Token，见 AI-02）；预留 MCP 2025-06-18 规范的 OAuth 2.1 资源服务器模式作为后续演进。
- **数据模型**：新表 `base_mcp_token`：`id, token_name, access_token(SHA-256 摘要存储), scope(readonly|readwrite), tool_whitelist(JSON), bind_user_id, rate_limit_per_min, expire_time, enabled, create_time`；管理端提供 Token CRUD 页面与调用统计。

### AI-02 MCP 工具集设计（P0，12 人日）

工具 = 平台管理面 Service 的封装（**不绕过现有参数校验/权限逻辑**），按业务流程分组；风险等级决定治理策略（见 AI-03）。

| 分组 | 工具名 | 类型 | 映射平台能力 | 风险 |
|---|---|---|---|---|
| 数据源 | `list_datasources` | read | `/sys/datasource/page`（密码字段剔除） | 低 |
| | `test_datasource_connection` | read | `/sys/datasource/test` | 低 |
| | `create_datasource` | write | `POST /sys/datasource` + 自动测试连接 | **高**（含凭据） |
| | `delete_datasource` | write | `DELETE /sys/datasource/{id}`（关联接口校验） | **高** |
| 元数据 | `list_schemas` / `list_tables` / `describe_table` | read | `/sys/metadata/schemas\|tables\|columns` | 低 |
| | `preview_table_data` | read | `/sys/test/running`（强制 `LIMIT`） | 低 |
| 接口开发 | `list_apis` / `get_api_detail` | read | `/sys/api/info/page`、`GET /sys/api/info` | 低 |
| | `create_api` / `update_api` | write | `POST /sys/api/info`（内部先调 `sqlParse` 自动生成参数定义） | 中 |
| | `run_sql_preview` | read | `/sys/test/running`（**仅 SELECT 白名单**） | 中 |
| | `test_api` | read | `/sys/test/verify/{apiId}` | 低 |
| 发布运营 | `publish_api` | write | `/sys/api/info/publish`（审批开启时转审批单） | **高** |
| | `set_api_state`（上线/下线） | write | `/sys/api/info/state` | 中 |
| | `destroy_api` | write | `/sys/api/info/destroy` | **高** |
| | `set_api_rate_limit` | write | `/sys/api/info/rateLimit` | 中 |
| 应用授权 | `list_apps` / `create_app` | read/write | `/sys/app*` | 中 |
| | `authorize_app_api` / `revoke_app_api` | write | `/sys/api/info/choosed` | 中 |
| 分组 | `list_groups` / `create_group` | read/write | `/sys/group*` | 低 |
| 日志监控 | `query_api_logs` | read | `/sys/api/log/page` | 低 |
| | `get_api_stats` | read | `/sys/api/log/summary\|dailyTrend\|topApis` | 低 |

**工具描述规范（直接影响 AI 调用成功率）**：
- 每个工具提供中英文双语 `description`，写明前置条件（如 `create_api` 前需 `list_datasources` 获取 datasourceId）、参数语义、失败语义；
- 返回对 LLM 友好：精简 JSON、列表默认 ≤20 条分页、大文本截断（SQL 脚本/响应体 ≤2000 字符）、错误返回可操作的提示（"datasourceId 无效，可调用 list_datasources 获取有效列表"）；
- 输出过滤：密码/appSecret 等敏感字段一律不返回给 LLM；元数据类返回附带"建议 SQL 模板"降低 AI 写错 SQL 概率。

**JSON Schema 示例（create_datasource）**：

```json
{
  "name": "create_datasource",
  "description": "在平台创建一个数据库数据源并自动测试连接。需提供数据库类型、JDBC 地址与凭据。创建成功返回 datasourceId，可用于后续 create_api。",
  "inputSchema": {
    "type": "object",
    "properties": {
      "datasource_name": { "type": "string", "description": "数据源显示名" },
      "datasource_type": { "type": "string", "enum": ["mysql", "postgresql", "oracle", "sqlserver", "dm", "clickhouse", "duckdb"] },
      "host": { "type": "string" }, "port": { "type": "integer" },
      "database": { "type": "string", "description": "库名/schema" },
      "username": { "type": "string" }, "password": { "type": "string", "description": "仅传输，不回显" }
    },
    "required": ["datasource_name", "datasource_type", "host", "port", "database", "username", "password"]
  }
}
```

### AI-03 AI 操作安全与治理（P0，8 人日）—— 与底座闭环

1. **身份与权限**：每个 AI 平台接入发放独立 MCP Token；Token 绑定平台用户（复用 `UserThreadLocal` 权限上下文），scope 分 `readonly`（仅查询/预览/日志类工具）与 `readwrite`；支持工具白名单细粒度收缩（如仅开放只读+创建接口、不含删除/发布）。
2. **危险操作双闸门**（任一即可放行，均开启时同时要求）：
   - **confirm 参数**：`publish_api`/`destroy_api`/`delete_datasource` 的 inputSchema 必填 `confirm: true`，迫使 AI 平台向最终用户二次确认；
   - **审批流闸门**（BASE-01 开启时）：AI 发起的发布自动生成审批单，人工审批通过才生效——AI 干活、人把关。
3. **AI 操作审计**：所有 `tools/call` 记入 `base_sys_audit_log`（`source=mcp`，含 `agent_session_id`、工具名、入参摘要、结果状态、耗时），审计页面可按 Token/会话筛选；审计页面同时展示该 Token 的调用量与失败率。
4. **频控与熔断**：Token 级限流（默认 30 次/分钟，复用 BASE-04 限流存储抽象）；单 Token 连续失败自动挂起。
5. **SQL 安全**：`run_sql_preview` 仅放行 SELECT 并强制 LIMIT；`create_api` 的 SQL 经现有危险 SQL 黑名单与 MyBatis 标签校验；文档建议 AI 接入的数据源使用只读账号。

### AI-04 AI 办公平台对接（P0，6 人日 + 联调）

- **接入包**：MCP 端点 URL（`https://<host>:9377/mcp`）、Token、传输协议说明；
- **对接指引文档**：workbuddy、千问办公（通义）MCP 配置步骤截图级文档 + Claude Desktop / Cursor / Dify 通用配置（stdio 启动命令与 env）；
- **兼容性验证清单**：千问办公 MCP、workbuddy、钉钉 AI 助理、飞书、Dify、Claude Desktop（stdio）、Cursor（stdio）——覆盖 streamable HTTP / SSE / stdio 三种 transport 的真实客户端；
- **演示脚本**：预置"创建数据源→开发接口→发布→授权→查日志"标准演示对话与演示数据集（含一个只读演示库的种子 SQL）。

### AI-05 AI 增值能力（P2，本期不排期，作为 v6.x 演进）

| 能力 | 说明 |
|---|---|
| 工作台 AI 助手（text2sql） | 页面内置对话侧栏：平台下发库表结构+few-shot 模板给 LLM（Key 由用户配置），生成 SQL 填入编辑器——SQL 生成由前端直连 LLM，不占用 MCP 链路 |
| 接口文档 AI 生成 | 基于 SQL 与参数自动生成接口说明/示例（导出时调用） |
| 日志 AI 分析 | 对失败日志聚类归因（慢 SQL 识别、参数错误模式），生成周报 |
| MCP Resources | 将接口目录、监控摘要作为 MCP resources 暴露，供 AI 主动订阅 |

---

## 五、开发计划

### 5.1 排期总览（12 周，后端 2 人 + 前端 1 人 + 测试 0.5 人）

| 周次 | 后端 | 前端 | 里程碑 |
|---|---|---|---|
| W1-W2 | BASE-03 安全加固；BASE-02 审计日志（注解+AOP+表） | 审计日志页面；登录改密引导 | |
| W3-W4 | BASE-01 审批流（表/状态机/自动发布）；BASE-05 版本回滚 | 审批中心；版本列表/diff/回滚 UI | **M1：v5.6.0（底座 P0）** |
| W5 | AI-01 MCP 基座（JSON-RPC 端点、session、Token 模型与鉴权） | MCP Token 管理页 | |
| W6 | AI-02 工具集第一批（数据源/元数据/接口 CRUD/预览，15 个） | — | |
| W7 | AI-02 工具集第二批（发布/授权/日志，8 个）+ 输出治理 | — | |
| W8 | AI-03 治理落地（confirm/审批联动/审计/频控）；AI-04 三 transport 适配 | 接入文档与配置页 | **M2：v6.0.0-beta（MCP GA）** |
| W9-W10 | BASE-04 集群化（Redis 限流/缓存广播）；BASE-09 测试补齐 | 集群配置项；文档页优化 | |
| W11 | BASE-06 OpenAPI 导入导出；BASE-08 告警 | OpenAPI 导入向导；告警规则页 | |
| W12 | BASE-07 脱敏与转换；全量回归 + 性能验证（对照 README 压测口径） | 脱敏配置 UI；发布整理 | **M3：v6.0.0 正式** |

> BASE-09 的前端构建自动化与 README 修正安排在 W1 顺手完成；接口编排、Mock、RBAC、多租户移入 v6.x 专项，不在本期。

### 5.2 工作量估算

| 需求 | 后端 | 前端 | 合计（人日） |
|---|---|---|---|
| BASE-01 审批流 | 8 | 4 | 12 |
| BASE-02 操作审计 | 6 | 2 | 8 |
| BASE-03 安全加固 | 10 | — | 10 |
| BASE-04 集群化 | 10 | — | 10 |
| BASE-05 版本回滚 | 4 | 2 | 6 |
| BASE-06 OpenAPI | 6 | 2 | 8 |
| BASE-07 脱敏转换 | 7 | 3 | 10 |
| BASE-08 告警 | 8 | — | 8 |
| BASE-09 工程质量 | 8 | — | 8 |
| AI-01 MCP 基座 | 10 | 2 | 12 |
| AI-02 工具集 | 12 | — | 12 |
| AI-03 安全治理 | 8 | 2 | 10 |
| AI-04 平台对接联调 | 6 | — | 6 |
| **合计** | **103** | **17** | **120** |

### 5.3 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| Spring AI MCP starter 与 Boot 4.1 兼容性未验证 | AI-01 选型 | 已定主路径为自研轻量 JSON-RPC 实现（协议面仅 4 个方法）；Spring SDK 作为备选并行 PoC，W5 首日决策 |
| 各 AI 办公平台 MCP 客户端规范/鉴权差异（transport、自定义 header 能力） | AI-04 联调阻塞 | 三 transport 全覆盖；Token 支持 header 与 query 双传递；W8 先与最开放的平台（Dify/Claude）打通再攻封闭平台 |
| AI 生成 SQL 的安全风险（误删数据、拖库） | 数据安全 | `run_sql_preview` SELECT 白名单 + 强制 LIMIT；危险 SQL 黑名单复用；发布必经 confirm/审批；接入文档强制建议只读账号 |
| 大结果集撑爆 LLM 上下文 | 工具体验 | 工具层统一分页（≤20 条）与字段裁剪；describe 优先（先表结构后数据） |
| 审批流改造影响现有发布链路 | 回归风险 | 功能开关默认关闭；`apiPublish` 原逻辑抽出复用；M1 回归全部发布场景 |
| 单机用户升级成本（新增 Redis 依赖） | 社区接受度 | 集群能力全部可开关，默认 single 模式零新依赖 |
| 12 周排期偏紧（120 人日/3.5 人） | 交付 | 脱敏（BASE-07）与告警（BASE-08）为可裁剪项，顺延至 v6.0.1；M2 里程碑不可裁剪 |

### 5.4 验收口径（版本级）

- **v5.6.0**：审批流开关闭环；全部写操作有审计；弱口令/硬编码密钥清零；双实例限流准确；版本可回滚。
- **v6.0.0**：在千问办公与 workbuddy 中完成"对话创建数据源→创建接口→发布（含审批）→查询日志"全流程演示；Token scope/限流/审计生效；兼容性清单 7 个客户端通过 ≥5 个。

---

## 六、附录

### 6.1 对话流程示例（千问办公）

```
用户：连一下测试库 orders，写个接口查最近7天每日订单量，发布到"订单"分组，AppKey 认证。
AI 调用链：
  create_datasource(mysql, 192.168.1.10:3306/orders)  → datasourceId=3（连接测试通过）
  describe_table(t_order)                              → 字段/索引结构
  run_sql_preview("SELECT DATE(create_time) d, COUNT(*) c FROM t_order
                    WHERE create_time >= DATE_SUB(CURDATE(), INTERVAL 7 DAY) GROUP BY d")
                                                       → 预览 7 行，耗时 12ms
  create_api(name=近7天每日订单量, path=/order/daily/stats, datasourceId=3, sql=..., auth=app_key)
                                                       → apiId=57，参数已自动解析
  publish_api(apiId=57, confirm=true)                  → 审批开启：生成审批单 #12
AI 回复：接口已创建并提交发布审批（审批单 #12），审批通过后将自动上线。已同步生成接口文档链接。
```

### 6.2 现状代码索引（撰写依据）

| 主题 | 位置 |
|---|---|
| 网关入口/鉴权链 | `crabc-api/crabc-core/.../app/api/ApiServiceController.java`、`app/filter/AuthInterceptor.java` |
| SQL 执行引擎 | `app/service/core/impl/BaseDataServiceImpl.java`、`crabc-datasource/.../driver/jdbc/JdbcStatement.java` |
| 动态数据源 | `crabc-datasource/.../DataSourceManager`、`config/JdbcDataSourceRouter.java` |
| 限流/缓存/日志 | `app/service/system/impl/ApiRateLimitService.java`、`app/config/CacheConfig.java` |
| 接口生命周期 | `app/service/system/impl/BaseApiInfoServiceImpl.java` |
| 数据模型 | `db/mysql.sql`（8 张表）、`db/update.sql`（增量） |
| 前端页面 | `crabc-web/src/views/serve/`（工作台）、`views/manage/`（管理）、`config/router.config.js` |
