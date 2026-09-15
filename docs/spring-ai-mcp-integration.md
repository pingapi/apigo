# ApiGo 集成 Spring AI 2.0（MCP Server）实施方案

| 项目 | 内容 |
|---|---|
| 版本 | **v2.0**（审查修订版） |
| 日期 | 2026-09-15 |
| 适用分支 | `dev`（Spring Boot 4.1.0 / JDK 21 / Jackson 3） |
| 目标 | ApiGo 作为 **MCP Server** 内嵌于主服务，让 WorkBuddy、千问办公等 AI 办公平台通过 MCP 连接平台，AI 对话直接完成「**建数据源 → 写 SQL → 建接口 → 发布 → 授权 → 查日志**」全业务流程 |
| 依据 | 对仓库源码的全量核对（拦截器/Service/Mapper/前端提交约定）+ Spring AI 2.0.1 官方文档核实 |

---

## 0. v1.0 → v2.0 修订说明

### 0.1 修订原因

v1.0 覆盖了「建数据源 → 建接口 → 发布」，但有三类问题：

1. **能力缺口**：缺 **SQL 试跑**、**应用授权**、**日志查询** 三类工具，无法完成标题所述全业务流程（v1.0 文档自身目标写的是"接口创建/编辑/发布/上下线、数据源管理、元数据查询"，与本次要求不一致）。
2. **与现有代码不符**（会导致实现即返工，共 8 处，见下表）。
3. **生产化缺口**：会话模式、`request-timeout`、Token 治理、审计挂载点、多平台传输兼容性约束均未交代。

### 0.2 逐条变更清单

| # | v1.0 结论 | v2.0 修订 | 依据（代码/文档） |
|---|---|---|---|
| 1 | 13 个工具，不含授权、日志 | **27 个工具**（18 个 P0 + 9 个 P1），覆盖建数据源→写 SQL→建接口→发布→授权→查日志 | 本次目标；`docs/ApiGo功能迭代需求文档与开发计划.md` AI-02 |
| 2 | `apigo_api_create` 「不做 SQL 参数解析，因 SQLUtil 逻辑内联在 controller，本方案不动 controller」 | **改为工具层复用 `SQLUtil` 静态方法自动解析参数**。推理不成立：`SQLUtil` 是纯静态工具类，无需改 controller 即可调用 | `ApiInfoController.sqlParse` 第 141-186 行仅调用 `SQLUtil.*` |
| 3 | 数据源创建参数为 `name`，未提密码编码 | **参数改为 `datasourceName`；`password` 必须先 Base64 编码再入库**。`addDataSource` 不做编码，但内部 `addCache` 会 `Base64.decode`，不编码将抛异常 | `DataSourceController.add` 第 40-47 行；`BaseDataSourceServiceImpl.addDataSource` 第 103-117 行 + `addCache` 第 134-141 行；前端 `DataSourceEdit.vue` 第 186-200 行 |
| 4 | 参数为 `host / port / databaseName` | **平台不按 host/port 拼 JDBC URL**，实体只认 `jdbcUrl`。工具层需按 `datasourceType` 模板拼装，并允许 `jdbcUrl` 直接覆盖 | `DefaultDataSourceDriver.createHikariDataSource` 第 118-122 行直接 `setJdbcUrl(ds.getJdbcUrl())` |
| 5 | `apigo_api_search` 返回 `datasourceName` | **修正**：`selectList` 不含数据源列，`datasourceName` 恒为 `null`。返回 `apiId/apiName/apiPath/apiMethod/apiStatus/enabled/groupName` | `BaseApiInfoMapper.xml` `selectList` 第 7-43 行 |
| 6 | 对话流为 `publish → set_enabled(1)` | **修正**：`apiPublish` 内部已置 `enabled=1`（发布即上线），再调 `set_enabled(1)` 冗余。`set_enabled` 只用于单独上下线 | `BaseApiInfoServiceImpl.apiPublish` 第 337 行 |
| 7 | `McpTokenInterceptor`（HandlerInterceptor）+ 拦截器内注入 `UserThreadLocal` | **改为 Servlet Filter 鉴权 + 工具层显式绑定用户上下文**。理由两点：① transport 端点注册方式未必是注解 Controller，Filter 覆盖全部情况；② Streamable-HTTP 存在异步分派可能，拦截器 `preHandle` 的线程不保证等于工具方法执行线程 | `InterceptorConfig` 第 50-59 行已有 `ApiFilter` 先例；Spring MVC 异步分派语义 |
| 8 | 未提超时/会话/能力开关 | 补充 `request-timeout`（默认 **20s** 必须调大）、`capabilities.*` 关闭、`protocol` 三选一不可共存 | Spring AI 2.0.1 官方文档 |
| 9 | 「SSE 改一行即可」 | **澄清**：同一进程只能启用一种 `protocol`。若某平台只支持旧 SSE，需**双实例**（同 jar 不同 profile） | 同 #8 |
| 10 | 「不做删除类高危操作」与需求文档 `delete_datasource / destroy_api` 冲突 | **仲裁**：本期以本方案为准（不暴露删除），差异记入 §15 | 本文档 §15 |
| 11 | Token 为 yml 静态列表，无来源区分 | 保留静态 Token 作为**阶段一**（快速可用），并给出**阶段二** `base_mcp_token` 表方案（scope / 工具白名单 / 审计 / 频控），与需求文档 AI-01/AI-03 对齐 | `docs/ApiGo功能迭代需求文档与开发计划.md` AI-01 |
| 12 | 未提审计 | MCP 变更类工具统一走 `McpAuditRecorder` 挂载点（当前落结构化日志，BASE-02 审计表落地后切换实现） | 同 #11 |

### 0.3 已核实为**准确**、予以保留的 v1.0 结论

- 技术栈兼容：Spring AI **2.0.1** 为当前 stable，要求 **Spring Boot 4.x + JDK 21 + Jackson 3（`tools.jackson`）**，与本分支完全对齐。
- 依赖坐标：`org.springframework.ai:spring-ai-starter-mcp-server-webmvc`。
- 端点与配置键：`spring.ai.mcp.server.protocol=STREAMABLE`、`spring.ai.mcp.server.type=SYNC`（默认值）、端点默认 `/mcp`。
- 路由无冲突：`JwtInterceptor` 只拦 `/api/box/**`，`AuthInterceptor` 只拦 `/api/web/**`，`/mcp` 不受影响。
- `apiPublish` 非草稿路径只依赖 `apiId`；已发布 + 有草稿时发布草稿并清空 `draft_content`。
- `UserThreadLocal.getUserId()` 是 `createBy/updateBy` 的唯一来源，返回值类型为 `String`。
- checked 异常不适合工具方法（会被包装为硬失败而非回传模型）。

---

## 1. 目标与非目标

### 1.1 目标场景（全业务流程）

> **用户在 WorkBuddy / 千问办公中对话：**
> 「连一下测试库 `192.168.1.10:3306` 的 `orders` 库（账号 readonly/xxx），写个接口查最近 7 天每日订单量，发布到『订单』分组，用 AppKey 认证，最后把刚才那条授权链路的调用日志拉出来看看。」
>
> **AI 通过 MCP 依次调用：**
> `apigo_datasource_create`（先连通性测试）→ `apigo_metadata_tables` / `apigo_metadata_columns`（取表结构）→ `apigo_sql_preview`（试跑 SQL 验证语法与结果）→ `apigo_api_create`（自动解析参数并建接口）→ `apigo_api_publish`（发布即上线）→ `apigo_app_create`（生成 AppKey/AppSecret）→ `apigo_app_authorize`（授权应用访问该接口）→ `apigo_log_query`（查调用日志）

覆盖六步：**建数据源 → 写 SQL → 建接口 → 发布 → 授权 → 查日志**。

### 1.2 目标

1. ApiGo 作为 **MCP Server** 内嵌（与主服务同进程、同端口 9377），通过 Streamable-HTTP 暴露 tools。
2. AI 办公平台侧只需配置 **1 个 URL + 1 个 Token** 即可接入，无需改造平台。
3. 不改动任何现有 Controller / Service / Mapper，**新增 `mcp` 适配层**，与 Web 控制台共用同一套 service（含全部参数校验、缓存失效、事务语义）。
4. 工具层承担三件事：参数扁平化、结果裁剪脱敏、异常语义化。业务逻辑零复制。

### 1.3 非目标（明确不做）

| 项 | 说明 |
|---|---|
| MCP OAuth2 / mcp-security | 官方 MCP Security 在 2.0.1 仍为 **WIP**；本期用静态 Bearer Token |
| MCP Resources / Prompts | 纯 Tools 足够，且关闭 `capabilities.resource/prompt` 可减少客户端探测噪音 |
| **删除类高危操作** | 不提供 `delete_datasource` / `destroy_api` / `delete_app` / `delete_group`（与需求文档 AI-02 的差异见 §15） |
| stdio 传输 | 目标客户端（WorkBuddy/千问办公）均为远程 HTTP 客户端；stdio 需独立启动器，列入 v6.x |
| 改造前端 / 新增管理页面 | 阶段一 Token 用 yml 配置，不做 MCP Token 管理页 |
| 多租户隔离 | 平台当前 `tenantId` 仅字段透传，无租户过滤（见 §14） |

---

## 2. 技术选型

### 2.1 候选方案对比

| 维度 | A. Spring AI Boot Starter（选定） | B. MCP Java SDK 手动组装 | C. 独立进程部署 |
|---|---|---|---|
| 做法 | `spring-ai-starter-mcp-server-webmvc` + `@McpTool` | 直接用 `io.modelcontextprotocol.sdk` 手写 Server | 新起一个只跑 MCP 的应用 |
| 优点 | 自动装配、注解即工具、会话/流式/通知全部内置、与 Boot 4.1 同代 | 控制力最强 | 隔离性好 |
| 缺点 | 依赖 Spring AI 版本节奏 | 样板代码多（JSON-RPC 分发、会话、Schema 生成全手写） | 需跨进程调 service，违背"共用 service"诉求 |
| 结论 | ✅ 选定 | 不必要 | ❌ |

### 2.2 关键决策

| 决策点 | 结论 | 理由 |
|---|---|---|
| Spring AI 版本 | **2.0.1**（stable） | 与 Spring Boot 4.1.0 / JDK 21 / Jackson 3 同代；MCP 注解已并入 `org.springframework.ai.mcp.annotation.*` |
| 传输协议 | **STREAMABLE**（Streamable-HTTP，MCP 2025-03-26 规范） | 面对远程 SaaS 客户端；端点默认 `POST/GET/DELETE /mcp` |
| 协议模式 | **有状态 STREAMABLE**（默认，支持 `Mcp-Session-Id`） | 单实例 / sticky session 部署即可；集群无 sticky 时改 `STATELESS`（见 §12） |
| 运行模式 | **SYNC** | 项目为阻塞式 WebMVC，service 全同步；`type` 默认即 `SYNC` |
| 代码位置 | **crabc-core 新增 `cn.crabc.core.app.mcp` 包** | 被 `@ComponentScan("cn.crabc.core")` 覆盖，与主应用同进程同端口 |
| 鉴权 | **静态 Bearer Token**（Servlet Filter 校验） | AI 平台配置一次即可，Token 不过期优于复用 8h 过期的 JWT |
| 用户归属 | 配置 `crabc.mcp.user-id`，**由工具层显式绑定到 `UserThreadLocal`** | 保证 `createBy/updateBy` 有主且线程确定（见 §5.2） |
| 工具范围 | **读 + 写，不含删除** | 高危操作留在控制台 |
| 结果约定 | 成功直接返回精简 DTO；失败抛 `CustomException` / `IllegalArgumentException` | 避免 `Result`（code/msg/data）语义混入模型上下文 |

### 2.3 版本与兼容性事实核查

| 项 | 核实结论 |
|---|---|
| Spring AI 2.0.1 | 官方文档标注 stable（同代还有 1.1.8 / 1.0.9，2.0.2-SNAPSHOT） |
| 基线要求 | Spring Boot **4.x**（升级说明中明确适配 Boot 4.1.x 的 HttpClient5 5.6 破坏性变更）、Java **21**、**Jackson 3（`tools.jackson`）** |
| MCP Java SDK | Spring AI 2.0 升级到 SDK **2.0.0**；服务端工具输入校验默认开启 |
| 本项目匹配度 | ✅ 完全匹配：Boot 4.1.0、JDK 21、`import tools.jackson.databind.json.JsonMapper`（`AuthInterceptor` 第 27 行、`BaseApiInfoServiceImpl` 第 29 行） |
| 依赖仓库 | 根 `pom.xml` 已配置 `spring-milestones` 仓库；GA 版本走中央仓库即可 |

---

## 3. 总体架构

### 3.1 架构图

```
WorkBuddy / 千问办公 / 钉钉AI / MCP Inspector
    │  HTTPS  POST|GET|DELETE /mcp   （Streamable-HTTP, JSON-RPC 2.0）
    │  Header: Authorization: Bearer <token>
    ▼
┌─ McpTokenFilter（新增，Servlet Filter，仅 /mcp*）──────────────┐
│  校验 token → 写 McpAuthContext（userId + tokenName + scope）  │
│  失败 → 401 + WWW-Authenticate（不进入 MCP 层）                │
└──────────────────────────────────────────────────────────────┘
    ▼
Spring AI MCP Server（WebMVC Streamable-HTTP, SYNC, 端点 /mcp）
    ▼
cn.crabc.core.app.mcp.tools.*        ← 新增工具适配层（@McpTool）
    │  McpToolSupport.bindUser(...)  ← 工具层显式绑定 UserThreadLocal
    │  职责：参数扁平化 / JDBC URL 拼装 / 密码编码 / SQL 参数解析 /
    │        结果裁剪脱敏 / 异常语义化 / 审计记录
    ▼
IBaseApiInfoService / IBaseDataSourceService / IBaseGroupService /
IBaseAppService / IBaseApiLogService / IBaseDataService / DataSourceManager
    │  ← 现有 service，零改动（Controller 与 tools 为平行入口）
    ▼
mapper / 动态数据源（HTTP 层不再经过 JwtInterceptor/AuthInterceptor）
```

### 3.2 设计要点

**1) tools 适配层不复制业务逻辑**，只做五件事：

- **参数扁平化**：AI 不擅长深层嵌套 JSON Schema。所有入参为标量或标量数组，适配层内部组装 `ApiInfoParam` / `BaseApiInfo` / `BaseDatasource` / `BaseApp` / `BaseAppApi` 等业务对象。
- **副作用装配**（v1.0 缺失，必须实现）：
  - `password` → `Base64.encode`（数据源）；
  - `host/port/databaseName` → 按类型模板拼 `jdbcUrl`；
  - `apiPath` → `IBaseApiInfoService.normalizeApiPath()`；
  - `sqlScript` → `SQLUtil` 解析出 `requestParam/responseParam`；
  - `appCode/appKey/appSecret` → UUID 生成（服务端不生成，见 §14）；
  - `ApiInfoParam.baseInfo.apiId == null` 判定新增 / 非 null 判定编辑。
- **结果裁剪/脱敏**：列表类只返回模型需要的字段，`pageSize` 上限 20；`password` 一律不返回；`apiSecret` 仅创建时回显一次；SQL 文本截断 2000 字符；单行数据最多 20 行。
- **异常语义化**：`CustomException`（继承 `RuntimeException`）与 `IllegalArgumentException` 按 Spring AI 契约转为 `isError=true` 的 `CallToolResult`，错误原文回传模型自纠重试。
- **审计记录**：所有变更类工具落一条结构化审计（详见 §5.5）。

**2) 用户上下文桥接（v1.0 修订点）**

`BaseApiInfoServiceImpl.addApiInfo / updateApiInfo / apiPublish / updateApiState / addChooseApi`、`BaseDataSourceServiceImpl.addDataSource`、`BaseAppServiceImpl.addApp`、`BaseGroupServiceImpl.addGroup` 均依赖 `UserThreadLocal.getUserId()` 写 `createBy/updateBy/delete` 维度。

v1.0 在拦截器 `preHandle` 里 `UserThreadLocal.set(...)`。**不可靠**：Streamable-HTTP 在 SSE 响应场景可能发生异步分派，工具方法的执行线程未必等于 `preHandle` 的线程；一旦不同线程，`getUserId()` 返回 `"0"`，`createBy` 全为 0，且 `baseAppApiMapper.delete(appId, "0")` 会导致授权覆盖失效。

**v2.0 方案**：鉴权与上下文分离。

- `McpTokenFilter`：只做鉴权，把解析结果放入 `McpAuthContext`（`ThreadLocal` + 请求属性双写）。
- `McpToolSupport.bindUser(Supplier)`：**每个工具方法的业务体都在这层包装内执行**，入口 `UserThreadLocal.set(Map.of("userId", <String>))`，`finally` 中 `remove()`。因此工具方法与 service 调用**必然同线程**，上下文 100% 可见。
- 兜底：取不到 `McpAuthContext` 时使用 `crabc.mcp.user-id`。

**3) 零侵入**：现有两个拦截器只覆盖 `/api/box/**` 与 `/api/web/**`；CORS 已由 `WebConfiguration.addCorsMappings("/**")` 覆盖 `/mcp`（`allowedHeaders("*")` 允许 `Authorization`），无需改动。回退时删新增包 + 还原 4 个增量文件即可。

### 3.3 新增与改动文件清单

新增（`crabc-api/crabc-core/src/main/java/cn/crabc/core/app/mcp/`）：

```
mcp/
├── config/
│   └── McpConfig.java              # McpTokenFilter 注册（FilterRegistrationBean）
├── auth/
│   ├── McpTokenFilter.java         # /mcp* 鉴权（Bearer Token）
│   └── McpAuthContext.java         # 当前调用的身份/scope/tokenName
├── support/
│   ├── McpToolSupport.java         # bindUser / 分页钳制 / 截断 / 脱敏 / Base64 / JDBC URL 模板
│   ├── McpAuditRecorder.java       # 变更类工具审计挂载点
│   └── SqlPreviewGuard.java        # SQL 试跑白名单校验（SELECT-only + 强制 LIMIT）
└── tools/
    ├── GroupTools.java             # 2 个
    ├── DataSourceTools.java        # 5 个
    ├── MetaDataTools.java          # 3 个
    ├── ApiDevTools.java            # 6 个
    ├── ApiOpsTools.java            # 4 个
    ├── AppTools.java               # 4 个
    └── LogTools.java               # 3 个
```

改动既有文件（4 个）：

| 文件 | 改动 |
|---|---|
| `pom.xml`（根） | 新增 `spring-ai.version=2.0.1` 属性 + `spring-ai-bom` BOM import |
| `crabc-api/crabc-core/pom.xml` | 新增 `spring-ai-starter-mcp-server-webmvc` |
| `crabc-api/crabc-admin/src/main/resources/application.yml` | 新增 `spring.ai.mcp.*` 与 `crabc.mcp.*` |

> **实际改动仅 3 个文件**（2 个 pom + 1 个 yml），比 v1.0 更少：`McpConfig` 是独立 `@Configuration`，被 `@ComponentScan("cn.crabc.core")` 自动扫描，因此 `InterceptorConfig` 与 `WebConfiguration` **均无需改动**。

---

## 4. 依赖与配置

### 4.1 根 `pom.xml`

```xml
<properties>
    <!-- 现有属性保持不变 -->
    <spring-boot.version>4.1.0</spring-boot.version>
    <!-- 新增 -->
    <spring-ai.version>2.0.1</spring-ai.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-dependencies</artifactId>
            <version>${spring-boot.version}</version>
            <type>pom</type><scope>import</scope>
        </dependency>
        <!-- 新增 -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type><scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### 4.2 `crabc-api/crabc-core/pom.xml`

```xml
<!-- 版本由 spring-ai-bom 管理，此处不写 version -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```

> 注意：只加依赖不设 `protocol` **不会**启用 Streamable 端点，必须配 §4.3 的 `protocol: STREAMABLE`。

### 4.3 `application.yml` 增量

```yaml
spring:
  ai:
    mcp:
      server:
        enabled: true
        name: apigo-mcp-server
        version: 5.5.0
        protocol: STREAMABLE          # STREAMABLE | STATELESS | SSE（三选一，不可共存）
        type: SYNC                    # 服务端只注册同步方法（默认即 SYNC）
        instructions: |
          ApiGo 是 SQL2API 接口开发平台。典型流程：
          apigo_datasource_list/create → apigo_metadata_tables/columns →
          apigo_sql_preview → apigo_api_create → apigo_api_publish →
          apigo_app_create → apigo_app_authorize → apigo_log_query。
          任何变更类操作前，请先向用户复述将要执行的变更并获得确认。
        request-timeout: 90s          # 关键：默认 20s < 平台 SQL 超时 30s，必须调大
        capabilities:
          resource: false             # 本期不提供 Resources
          prompt: false               # 本期不提供 Prompts
          completion: false
          tool: true
        resource-change-notification: false
        prompt-change-notification: false
        tool-change-notification: false
        streamable-http:
          mcp-endpoint: /mcp          # 默认值，显式写出便于阅读
          disallow-delete: false      # 允许客户端 DELETE 终止会话
        annotation-scanner:
          enabled: true               # 自动扫描 @McpTool

crabc:
  mcp:
    enabled: true                     # false = 不注册 Filter，/mcp 全部 401（等同禁用）
    token: "${MCP_TOKEN:}"            # 逗号分隔多 token；建议由环境变量注入，勿提交明文
    user-id: "1"                      # MCP 操作归属用户（默认 admin 的 userId）
    audit-enabled: true               # 变更类工具记审计日志
    max-page-size: 20                 # 工具层分页硬上限
    max-text-length: 2000             # SQL/文本截断长度
```

### 4.4 配置项说明

| 配置 | 默认 | 说明 |
|---|---|---|
| `spring.ai.mcp.server.protocol` | — | **必填**。`STREAMABLE` 有状态 / `STATELESS` 无状态 / `SSE` 旧协议。**同一进程只能启用一种** |
| `spring.ai.mcp.server.type` | `SYNC` | 同步服务端只接受非响应式返回值的方法 |
| `spring.ai.mcp.server.request-timeout` | `20s` | **必须 ≥ 60s**。平台单条 SQL 执行超时 30s（`JdbcStatement` 侧），元数据反射可能更慢 |
| `spring.ai.mcp.server.capabilities.*` | 全 `true` | 关闭 resource/prompt/completion 可减少客户端探测与 `tools/list` 外的调用面 |
| `spring.ai.mcp.server.streamable-http.mcp-endpoint` | `/mcp` | 可改为 `/api/mcp`（同样不与现有前缀冲突），本方案保留默认值 |
| `spring.ai.mcp.server.streamable-http.keep-alive-interval` | `null` | 保活仅对 SSE 长连接生效；若客户端频繁断连可设 `30s` |
| `spring.ai.mcp.server.annotation-scanner.enabled` | `true` | 关闭后 `@McpTool` 全部失效 |
| `crabc.mcp.token` | 空 | 建议 `${MCP_TOKEN:}` 环境变量注入；为空时 Filter 对全部请求 401 并 `log.error` 提示 |
| `crabc.mcp.user-id` | `1` | 写入 `UserThreadLocal` 的 `userId`，决定 `createBy/updateBy` |

### 4.5 AI 平台侧接入配置

| 客户端 | 配置 |
|---|---|
| WorkBuddy / 千问办公 | MCP Server URL：`https://<host>/mcp`（公网必须 HTTPS 反代，`Authorization` 头需经反代透传）<br>Header：`Authorization: Bearer <token>`<br>Transport：Streamable HTTP |
| 钉钉 AI / Dify | 同上；若平台只支持 SSE，需另起一个 `protocol=SSE` 实例（见 §12） |
| MCP Inspector（联调） | `npx @modelcontextprotocol/inspector` → Transport: Streamable HTTP，URL `http://127.0.0.1:9377/mcp`，Headers 加 `Authorization` |

> 反代注意：需保留 `Mcp-Session-Id` 请求/响应头、允许 `DELETE` 方法、关闭响应缓冲（`proxy_buffering off`），否则会话与 SSE 通知会异常。

---

## 5. 鉴权、上下文与安全

### 5.1 McpTokenFilter（鉴权）

```
doFilter(request, response, chain):
  1. method == OPTIONS → chain.doFilter()（放行 CORS 预检，CORS 已由 WebConfiguration 覆盖 /mcp）
  2. 解析 token：优先 Authorization: Bearer <token>，兼容裸 token
     （注意：Spring AI 2.0 对 WebMvc transport 的 header 名做了小写化处理，
       取 header 时统一按大小写不敏感方式读取）
  3. token 不在 crabc.mcp.token 集合中：
       401 + WWW-Authenticate: Bearer realm="apigo-mcp"
       Content-Type: application/json;charset=UTF-8
       body: {"code":402,"msg":"用户未登录"}   ← 复用 ErrorStatusEnum.JWT_UN_AUTH
       return（不进入 MCP 层）
  4. 通过：McpAuthContext.set(userId, tokenName, scope)
       ├─ ThreadLocal（工具层兜底读）
       └─ request.setAttribute（同请求内可读）
     chain.doFilter()
  5. finally：McpAuthContext.clear()
```

注册（`McpConfig`，与 `InterceptorConfig` 的 `ApiFilter` 同风格）：

```
FilterRegistrationBean<McpTokenFilter>
  addUrlPatterns("/mcp", "/mcp/*")     // 覆盖 POST/GET/DELETE 与自定义端点
  setOrder(Ordered.HIGHEST_PRECEDENCE) // 早于业务链
  仅当 crabc.mcp.enabled=true 时注册
```

> **为什么用 Filter 而不是 HandlerInterceptor（v1.0 修订）**：Streamable-HTTP 端点由 Spring AI 的 transport provider 注册，其 Handler 类型不确定（可能是 `RouterFunction`，也可能是裸 Servlet）；Filter 对所有 servlet 请求生效，不依赖 Handler 类型，也不受异步分派影响。项目已有 `ApiFilter` 作为先例，风格一致。

### 5.2 McpToolSupport.bindUser（用户上下文绑定）

```java
/** 所有 @McpTool 业务体必须在本包装内执行：保证与 service 调用同线程。 */
public <T> T bindUser(String toolName, Supplier<T> action) {
    String userId = McpAuthContext.userId();        // 兜底：crabc.mcp.user-id
    UserThreadLocal.set(Map.of("userId", userId, "userName", "mcp-" + toolName));
    try {
        T result = action.get();
        McpAuditRecorder.record(toolName, userId, true, null);
        return result;
    } catch (RuntimeException e) {
        McpAuditRecorder.record(toolName, userId, false, e.getMessage());
        throw e;                                    // 交给 Spring AI 转 isError=true
    } finally {
        UserThreadLocal.remove();                   // 虚拟线程复用，必须清理
    }
}
```

**注意 `UserThreadLocal` 的两个坑（已在 §14 记录）**：

- `getUserId()` 实现为 `map.get("userId").toString()`，**map 非空但没有 `userId` key 会 NPE**。因此绑定必须写 `userId`，不要只写 `userName`。
- `isAdmin()/isSuperAdmin()` 依赖 `role` key，而 JWT 从未写入 `role`，实际调用会 NPE。本方案**不使用**这两个方法。

### 5.3 Token 治理

**阶段一（本方案，快速可用）**：yml 静态 Token 列表 + 单一 `user-id`。

- 优点：零表结构、零前端改动、AI 平台配一次永久可用。
- 局限：无法区分调用来源、无 scope 分级、无频控、无调用统计。

**阶段二（生产化，与需求文档 AI-01/AI-03 对齐）**：

新表 `base_mcp_token`：

| 字段 | 说明 |
|---|---|
| `id` / `token_name` | 主键 / 可读名称（如 `workbuddy-prod`） |
| `access_token_hash` | Token 的 SHA-256 摘要（**不存明文**） |
| `scope` | `readonly` / `readwrite` |
| `tool_whitelist` | JSON 数组，为空表示按 scope 全量 |
| `bind_user_id` | 绑定的平台用户（替代 `crabc.mcp.user-id`） |
| `rate_limit_per_min` | 频率上限（默认 30） |
| `expire_time` / `enabled` | 有效期 / 开关 |

`McpTokenFilter` 改为查该表（Caffeine 缓存 5 分钟），并在 Filter 层完成 **scope / 白名单 / 频控** 校验；工具层再取 `bind_user_id` 作为归属用户。

### 5.4 安全边界

| 威胁 | 对策 |
|---|---|
| 未授权调用 tools | Filter 层 Bearer 校验，401 不进入 MCP 层 |
| 删除类高危操作 | 不提供 delete/destroy 工具（本期） |
| AI 误发布 | `apigo_api_publish` 的 description 强制要求「先向用户复述变更并确认」；阶段二叠加审批流闸门 |
| 数据源密码泄露到模型上下文 | `apigo_datasource_list` 依赖 SQL 自带 `'******' as password`；`apigo_datasource_get`（返回真实密码）工具层必须置空；所有返回路径统一走 `McpToolSupport.maskPassword()` |
| 应用密钥泄露 | `appSecret` 仅在 `apigo_app_create` 的**当次返回**中回显（用户需要抄走），`apigo_app_list` 一律脱敏 |
| 危险 SQL | `apigo_sql_preview` 强制 SELECT-only（复用 `SQLUtil.previewCheckSql`）+ 工具层追加 LIMIT；**不能只依赖 `JdbcStatement.validateSqlSafety`**——它对多语句只 `log.warn` 不拦截（详见 §7.3） |
| 上下文爆炸 | 分页默认 10 / 上限 20；SQL 截断 2000 字符；预览数据最多 20 行 × 30 列 |
| 线程串号 | `bindUser` 的 `finally` 强制 `UserThreadLocal.remove()` |
| Token 明文入仓 | yml 用 `${MCP_TOKEN:}` 占位，运维注入；README 提示 |

### 5.5 审计挂载点

`McpAuditRecorder` 接口在 §5.2 的 `bindUser` 中被调用，记录字段：`toolName / userId / tokenName / 入参摘要 / 成功失败 / 耗时 / 时间`。

- **当前实现**：结构化 `log.info("[MCP-AUDIT] ...")` 落 `logs/crabc/info.log`（`logback.xml` 未配 MDC，因此审计字段写在消息体内，便于 grep）。
- **后续切换**：BASE-02 的 `base_sys_audit_log` 表落地后，将实现替换为落库（`source=mcp`），接口不变。

---

## 6. 工具清单（27 个）

### 6.1 命名与注解规范

```java
@McpTool(
    name = "apigo_api_create",
    description = """
        创建一个 SQL 接口（保存为草稿状态，需再调用 apigo_api_publish 发布）。
        前置条件：需先通过 apigo_datasource_list 获取 datasourceId；
                建议先用 apigo_metadata_tables / apigo_metadata_columns 确认表与字段。
        SQL 中的 #{xxx} 与 ${xxx} 占位符会被自动解析为请求参数，无需手工定义。
        失败语义：apiPath 已存在时返回错误，可更换 apiPath 或先用 apigo_api_search 查询。
        """,
    annotations = @McpTool.McpAnnotations(
        title = "创建接口",
        readOnlyHint = false,
        destructiveHint = false,   // 默认是 true！必须显式声明，否则客户端标记为破坏性操作
        idempotentHint = false,
        openWorldHint = false))
public Map<String, Object> apigoApiCreate(
        @McpToolParam(description = "接口名称", required = true) String apiName,
        @McpToolParam(description = "接口路径，不要以 / 开头，如 order/daily/stats", required = true) String apiPath,
        @McpToolParam(description = "HTTP 方法：GET/POST/PUT/DELETE", required = false) String apiMethod,
        @McpToolParam(description = "数据源ID", required = true) String datasourceId,
        @McpToolParam(description = "SQL 脚本，支持 MyBatis 动态标签", required = true) String sqlScript,
        @McpToolParam(description = "分组ID，缺省为默认分组1", required = false) Integer groupId) {
    return support.bindUser("apigo_api_create", () -> doCreate(...));
}
```

规范要点：

- 工具名统一 `apigo_` 前缀 + `域_动作`；**名称唯一**（Spring AI 按名称去重、保留首次出现的工具）。
- `description` 必须写明：**前置条件**（依赖哪个工具取 id）、**参数语义**、**失败语义**（可否重试、如何自纠）、**是否产生变更**。这是 AI 调用成功率的第一影响因素。
- `destructiveHint` 默认 `true`。只读工具必须显式 `readOnlyHint = true, destructiveHint = false`；变更工具必须显式 `destructiveHint = false`，否则客户端会把工具标红或强制二次确认。
- 参数用 `@McpToolParam(description=..., required=...)`，description 面向模型（不是面向人）。
- **返回值**：成功返回精简 `Map`/自定义 record；失败抛 `RuntimeException` 子类。**不要用 `Optional`**（无参可空用 `required=false`，返回 `null` 会被序列化为 `null`，模型可理解）。
- **不要放 Spring 上下文参数**：`McpSyncRequestContext` 等在有状态模式下可注入，但本方案不依赖它（保证 STATELESS 模式可平滑切换）。

### 6.2 GroupTools（2 个）

| 工具 | 参数 | 说明 | 底层 service |
|---|---|---|---|
| `apigo_group_list` | `apiId?` | 分组树（含各组下接口 `apiId/apiName/apiStatus`），用于取 `groupId` | `IBaseGroupService.groupTree(userId, apiId)` |
| `apigo_group_create` | `groupName`, `parentId=0`, `groupDesc?` | 新建分组；返回 `groupId` | `IBaseGroupService.addGroup(BaseGroup)` |

> 返回裁剪：剔除 `children` 中的空数组、`apis` 只保留 `apiId/apiName/apiStatus`。

### 6.3 DataSourceTools（5 个）

| 工具 | 参数 | 说明 | 底层 service |
|---|---|---|---|
| `apigo_datasource_list` | `keyword?`, `pageNum=1`, `pageSize=10(≤20)` | 数据源分页；返回 `datasourceId/datasourceName/datasourceType/host/port/remarks`（`password` 由 SQL 固定为 `******`） | `getDataSourcePage` |
| `apigo_datasource_get` | `datasourceId` | 单个数据源（**必要**：`getDataSource` 返回真实密码，供 `apigo_sql_preview` 补 `datasourceType`；工具层必须置空 `password`） | `getDataSource(Integer)` |
| `apigo_datasource_test` | `datasourceType`, `host`, `port`, `databaseName?`, `username`, `password`, `jdbcUrl?` | 连通性测试，返回成功或失败原文 | `test(BaseDatasource)` |
| `apigo_datasource_create` | `datasourceName`, `datasourceType`, `host`, `port`, `databaseName`, `username`, `password`, `remarks?`, `jdbcUrl?` | **先 test 通过再 add**；`password` 工具层 Base64 编码；`jdbcUrl` 缺省时按类型模板拼装 | `test` + `addDataSource` |
| `apigo_datasource_update`（P1） | `datasourceId` + 上述字段（`password` 省略=不改） | 修改数据源 | `updateDataSource` |

> `datasourceType` 枚举（前端 `DataSourceType.vue`）：`mysql / oracle / sqlserver / postgresql / sybase / tidb / opengauss / dm / doris / starrocks / dolphindb / duckdb / clickhouse / custom`。
> 默认端口：mysql|tidb|starrocks 3306、doris 9030、postgresql|opengauss 5432、sqlserver 1433、oracle 1521、dm 5236、dolphindb 8848、clickhouse 8123。

### 6.4 MetaDataTools（3 个）

| 工具 | 参数 | 说明 | 底层 |
|---|---|---|---|
| `apigo_metadata_schemas` | `datasourceId` | 库/schema 列表 | `DataSourceManager.getMetaData(id)`（CATALOG 型走 `getCatalogs`，否则 `getSchemas`） |
| `apigo_metadata_tables` | `datasourceId`, `schema` | 表列表；裁剪为 `tableName/remarks/tableType` | 同左（CATALOG 型 `schema` 即 catalog） |
| `apigo_metadata_columns` | `datasourceId`, `schema`, `table` | 字段列表；裁剪为 `columnName/columnType/columnSize/remarks` | 同左 |

- 分支判定严格对齐 `MetaDataController`：`BaseConstant.CATALOG_DATA_SOURCE = [sybase, mysql, mariadb, doris, starrocks, tidb, tdsql]` 走 `getCatalogs/getTables(id,schema,null)/getColumns(id,schema,null,table)`（`schema` 位置传 catalog）；其余走 `getSchemas/getTables(id,null,schema)/getColumns(id,null,schema,table)`。
- `datasourceType` 不需要 AI 传，工具层用 `getDataSource(datasourceId).getDatasourceType()` 补齐（`MetaDataController` 里由前端传参，MCP 侧自动取）。

### 6.5 ApiDevTools（6 个）—— 写 SQL 与建接口

| 工具 | 参数 | 说明 | 底层 |
|---|---|---|---|
| `apigo_sql_parse` | `sqlScript`, `datasourceType?` | **不执行**，解析出请求参数名与返回列名，让 AI 先自检 SQL 与占位符 | `SQLUtil.*`（等价 `ApiInfoController.sqlParse`） |
| `apigo_sql_preview` | `datasourceId`, `sqlScript`, `paramsJson?` | **试跑 SQL**（仅 SELECT，工具层再追加 LIMIT），返回 `metadata`（列名）+ `data`（≤20 行） | `SQLUtil.previewCheckSql` + `IBaseDataService.sqlPreview(id, type, schema, sql)` |
| `apigo_api_search` | `keyword?`, `status?`, `pageNum=1`, `pageSize=10(≤20)` | 接口分页；返回 `apiId/apiName/apiPath/apiMethod/apiStatus/enabled/groupName` | `getApiPage(keyword, status, ...)` |
| `apigo_api_detail` | `apiId` | 完整详情（`baseInfo` + `sqlInfo` + `requestParam` + `responseParam` + `hasDraft`）；SQL 截断 2000 字符 | `getApiInfo(apiId)` |
| `apigo_api_create` | `apiName`, `apiPath`, `datasourceId`, `sqlScript`, `apiMethod=GET`, `groupId?`, `authType=NONE`, `pageSetup=0`, `resultType?`, `remarks?`, `schemaName?`, `tableName?` | 建接口：`apiPath` 归一化 → `checkApiPath` 冲突则抛错 → `SQLUtil` 自动解析参数 → 组装 `ApiInfoParam` → `addApiInfo`；返回 `apiId`（状态 `edit`、`enabled=0`） | `checkApiPath` + `addApiInfo` |
| `apigo_api_update`（P1） | `apiId` + create 的可选字段 | 编辑接口：先用 `getApiInfo` 回填未传字段再覆盖；**已发布接口自动写入草稿 `draft_content`，不改变线上行为** | `getApiInfo` + `updateApiInfo` |

**`status` 取值**（`ApiStateEnum`）：`edit / audit / release / destroy / history`。注意 `devType` 参数在 mapper 里就是 `api_status` 等值过滤，AI 常混淆，description 必须写清。

### 6.6 ApiOpsTools（4 个）—— 发布与运营

| 工具 | 参数 | 说明 | 底层 |
|---|---|---|---|
| `apigo_api_publish` | `apiId` | 发布。**发布即上线**（`api_status=release` + `enabled=1`）；若该接口「已发布且存在草稿」，则发布草稿并清空 `draft_content` | `apiPublish(ApiInfoParam)` |
| `apigo_api_set_enabled` | `apiId`, `enabled(1=上线/0=下线)` | 单独上下线（不改变 `api_status`） | `updateApiState(apiId, null, enabled)` |
| `apigo_api_set_rate_limit`（P1） | `apiId`, `windowValue?`, `windowUnit?`, `limitCount?` | 配置/清空接口限流（`windowUnit ∈ SECOND/MINUTE/HOUR`；`windowValue` 与 `limitCount` 同时为空=清空限流） | `getRateLimit` / `saveRateLimit` |
| `apigo_api_test`（P1） | `apiId`, `paramsJson?` | 按接口定义直接执行 SQL 自测（不经过 HTTP 网关），返回结果或异常原文 | `IBaseDataService.execute(datasourceId, datasourceType, schema, sql, params)` |

> 高亮 §0.2 #6：`apigo_api_publish` 之后**不需要**再调 `apigo_api_set_enabled(1)`。

### 6.7 AppTools（4 个）—— 授权

| 工具 | 参数 | 说明 | 底层 |
|---|---|---|---|
| `apigo_app_list` | `appName?`, `pageNum=1`, `pageSize=10(≤20)` | 应用列表；`appSecret` 一律脱敏为 `******`，返回 `appId/appName/appCode/appKey/enabled` | `appPage` |
| `apigo_app_create` | `appName`, `appDesc?` | 创建应用。**`appCode/appKey/appSecret` 由工具层用 UUID 生成**（服务端 `addApp` 只入库不生成），返回 `appId` + 三件套（**仅本次回显**） | `addApp(BaseApp)` |
| `apigo_app_authorize` | `appId`, `apiIds[]` | 授权应用访问接口。**全量覆盖语义**：先按 `(appId, 当前userId)` 删除旧授权，再批量插入 `apiIds`；`apiIds` 为空=清空授权 | `addChooseApi(BaseAppApi)` |
| `apigo_app_apis`（P1） | `appId`, `pageNum=1`, `pageSize=10(≤20)` | 查询该应用已授权的接口列表 | `getChooseApi(appId, ...)` |

> ⚠️ **`apigo_app_authorize` 的两个陷阱，务必在 description 中说明并在工具层做防护**：
> 1. **全量覆盖**而非增量追加。AI 若只想加一个接口却只传了 `apiIds=[新接口]`，会**清掉该应用其它接口的授权**。工具实现应先 `getChooseApi` 查已有集合，与入参合并后再提交（除非调用方显式传 `replaceAll=true`）。
> 2. `BaseAppApiMapper.delete` 的条件是 `app_id = ? AND create_by = ?`。若 MCP 的 `user-id` 与控制台操作者不一致，旧的他人授权记录删不掉，会**堆积重复记录**。因此 `crabc.mcp.user-id` 应与平台日常维护者保持一致（部署时确认），此约束写入 README。

### 6.8 LogTools（3 个）—— 查日志

| 工具 | 参数 | 说明 | 底层 |
|---|---|---|---|
| `apigo_log_query` | `keyword?`, `appName?`, `result?(success/fail)`, `startTime?`, `endTime?`, `pageNum=1`, `pageSize=10(≤20)` | 调用日志分页；返回 `apiName/apiPath/requestStatus/costTime/appName/requestIp/requestTime`（响应体、请求参数不入模型上下文） | `IBaseApiLogService.page(ApiLogParam)` |
| `apigo_log_summary`（P1） | `keyword?`, `startTime?`, `endTime?` | 汇总：总量/成功/失败/平均耗时/最大耗时 | `summary(ApiLogParam)` |
| `apigo_stats_top`（P1） | `dimension(apis/costApis/ips/status)`, `startTime?`, `endTime?` | Top 排行 / 状态分布 | `topApis` / `topCostApis` / `topIps` / `statusPie` |

> **能力边界（必须写进 description，否则 AI 会反复试错）**：
> - `ApiLogParam` **只有** `result / keyword / appName / startTime / endTime / pageNum / pageSize`。
> - **不支持按 `apiId` 过滤**——只能按接口名/路径关键字（`keyword`）搜索。
> - **不支持按耗时、IP 过滤**（耗时仅出现在汇总与 Top 统计里，IP 仅出现在 `topIps`）。
> - `startTime` 与 `endTime` **必须成对出现**才生效；统计接口未传时间时默认取近 7 天。

### 6.9 工具总览

| 域 | P0 | P1 | 小计 |
|---|---|---|---|
| GroupTools | `group_list` | `group_create` | 2 |
| DataSourceTools | `datasource_list` / `datasource_test` / `datasource_create` | `datasource_get` / `datasource_update` | 5 |
| MetaDataTools | `metadata_schemas` / `metadata_tables` / `metadata_columns` | — | 3 |
| ApiDevTools | `sql_parse` / `sql_preview` / `api_search` / `api_detail` / `api_create` | `api_update` | 6 |
| ApiOpsTools | `api_publish` / `api_set_enabled` | `api_set_rate_limit` / `api_test` | 4 |
| AppTools | `app_list` / `app_create` / `app_authorize` | `app_apis` | 4 |
| LogTools | `log_query` | `log_summary` / `stats_top` | 3 |
| **合计** | **18** | **9** | **27** |

---

## 7. 关键工具实现要点

### 7.1 数据源创建（`apigo_datasource_create`）

```java
private Map<String, Object> doCreate(DataSourceCreateCmd cmd) {
    requireText(cmd.datasourceName(), "datasourceName");
    requireText(cmd.username(), "username");

    // 1) jdbcUrl：优先用入参，否则按类型模板拼（平台只认 jdbcUrl，不认识 host/port）
    String jdbcUrl = StringUtils.hasText(cmd.jdbcUrl())
            ? cmd.jdbcUrl()
            : buildJdbcUrl(cmd.datasourceType(), cmd.host(), cmd.port(), cmd.databaseName());

    // 2) password 必须 Base64 编码：addDataSource 内部 addCache() 会 Base64.decode()
    BaseDatasource ds = new BaseDatasource();
    ds.setDatasourceName(cmd.datasourceName());
    ds.setDatasourceType(cmd.datasourceType());
    ds.setJdbcUrl(jdbcUrl);
    ds.setHost(cmd.host());
    ds.setPort(cmd.port());
    ds.setUsername(cmd.username());
    ds.setPassword(Base64.getEncoder().encodeToString(cmd.password().getBytes(StandardCharsets.UTF_8)));
    ds.setRemarks(cmd.remarks());

    // 3) 先测后建：test() 内部会 Base64.decode → 与入库编码保持一致
    String testResult = dataSourceService.test(ds);
    if (!"1".equals(testResult)) {
        throw new CustomException(44003, "数据源连接测试失败：" + testResult);
    }
    dataSourceService.addDataSource(ds);
    return Map.of("datasourceName", cmd.datasourceName(), "jdbcUrl", jdbcUrl, "testPassed", true);
}
```

JDBC URL 模板（工具层内置）：

| 类型 | 模板 |
|---|---|
| mysql / tidb / starrocks | `jdbc:mysql://{host}:{port}/{db}?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true` |
| doris | `jdbc:mysql://{host}:{port}/{db}`（默认端口 9030） |
| postgresql / opengauss | `jdbc:postgresql://{host}:{port}/{db}` |
| oracle | `jdbc:oracle:thin:@{host}:{port}:{db}` |
| sqlserver | `jdbc:sqlserver://{host}:{port};databaseName={db}` |
| dm | `jdbc:dm://{host}:{port}/{db}` |
| clickhouse | `jdbc:clickhouse://{host}:{port}/{db}` |
| duckdb | 由 `jdbcUrl` 直接给出（文件路径），不支持 host/port |

> `datasourceType=custom` 时**必须**传完整 `jdbcUrl`（service 会用 `SQLUtil.getDataSourceType(jdbcUrl)` 反推类型）。

### 7.2 接口创建（`apigo_api_create`）

```java
private Map<String, Object> doCreate(ApiCreateCmd cmd) {
    requireText(cmd.apiName(), "apiName");
    requireText(cmd.apiPath(), "apiPath");
    requireText(cmd.datasourceId(), "datasourceId");
    requireText(cmd.sqlScript(), "sqlScript");

    // 1) 路径归一化：复用 service 的静态方法（去掉前导 "/"）
    String apiPath = IBaseApiInfoService.normalizeApiPath(cmd.apiPath());
    String method  = StringUtils.hasText(cmd.apiMethod()) ? cmd.apiMethod().toUpperCase() : "GET";

    // 2) 冲突校验：checkApiPath 返回 true 表示"已存在"
    if (Boolean.TRUE.equals(apiInfoService.checkApiPath(null, apiPath, method))) {
        throw new CustomException(50011, "接口地址已存在：" + method + " " + apiPath
                + "。可先用 apigo_api_search 查询，或更换 apiPath。");
    }

    // 3) 补齐 datasourceType / schema（metadata 分支也需要）
    BaseDatasource ds = dataSourceService.getDataSource(Integer.valueOf(cmd.datasourceId()));

    // 4) SQL 参数自动解析（等价 ApiInfoController#sqlParse，直接复用 SQLUtil 静态方法）
    SqlParseVO parsed = parseSql(cmd.sqlScript(), ds.getDatasourceType());

    // 5) 组装 ApiInfoParam（baseInfo + sqlInfo + requestParam + responseParam）
    ApiInfoParam param = new ApiInfoParam();
    BaseApiInfo base = new BaseApiInfo();
    base.setApiName(cmd.apiName());
    base.setApiPath(apiPath);
    base.setApiMethod(method);
    base.setAuthType(StringUtils.hasText(cmd.authType()) ? cmd.authType() : "NONE");
    base.setGroupId(cmd.groupId() == null ? 1 : cmd.groupId());
    base.setPageSetup(cmd.pageSetup() == null ? 0 : cmd.pageSetup());
    base.setRemarks(cmd.remarks());
    // base.setApiId(null) —— 必须为 null，Controller 以此判定新增
    param.setBaseInfo(base);

    BaseApiSql sql = new BaseApiSql();
    sql.setDatasourceId(cmd.datasourceId());
    sql.setDatasourceType(ds.getDatasourceType());
    sql.setSchemaName(cmd.schemaName() == null ? "" : cmd.schemaName());
    sql.setTableName(cmd.tableName() == null ? "" : cmd.tableName());
    sql.setSqlScript(cmd.sqlScript());
    param.setSqlInfo(sql);

    param.setRequestParam(toReqParams(parsed.getReqColumns(), cmd.datasourceId()));
    param.setResponseParam(toResParams(parsed.getResColumns(), cmd.datasourceId()));

    Long apiId = apiInfoService.addApiInfo(param);
    return Map.of("apiId", apiId, "apiPath", "/api/web/" + apiPath,
                  "apiMethod", method, "apiStatus", "edit", "enabled", 0,
                  "requestParamNames", parsed.getReqColumns());
}
```

`parseSql` 的 8 步（**逐行复刻 `ApiInfoController.sqlParse` 第 143-186 行的语义，不修改 controller**）：

1. 去掉末尾分号；
2. `sql.contains("</foreach>")` → `SQLUtil.extractForeachParams`；
3. `sql.contains("<if ") || contains("<when ")` → `SQLUtil.extractIfParams`；
4. `replaceAll("<foreach[\\s\\S]*?</foreach>", "()")`；
5. `SQLUtil.parseParams` → 得到全部 `#{}/${}` 参数名；
6. `SQLUtil.sqlFilter` → 剥离 MyBatis 标签；
7. `SQLUtil.completeSql` → 校验前补全；
8. `SQLUtil.parseResultColumns(sql, datasourceType)` + `SQLUtil.buildColumnInfo` → 返回列（跳过以 `*` 开头者）。

> 生成 `BaseApiParam` 时：`paramModel` 取 `request` / `response`；`paramName` 用解析出的名字；`paramType` 默认 `"String"`；`required` 默认 1（与前端行为一致，AI 可在 `apigo_api_update` 中调整）。`ApiInfoParam.getRequestParam()` 仅在 `addApiInfo`/`updateApiInfo` 中通过 `insertApiParams` 入库。

### 7.3 SQL 试跑（`apigo_sql_preview`）

```java
private Map<String, Object> doPreview(PreviewCmd cmd) {
    BaseDatasource ds = dataSourceService.getDataSource(Integer.valueOf(cmd.datasourceId()));

    // 1) SELECT-only 白名单：复用已有工具方法 previewCheckSql
    if (!SQLUtil.previewCheckSql(cmd.sqlScript(), ds.getDatasourceType())) {
        throw new CustomException(40011, "试跑仅允许 SELECT 语句。如需变更数据，请创建 DML 接口并由人工确认。");
    }
    // 2) 单语句校验：JdbcStatement.validateSqlSafety 对多语句仅 log.warn 不拦截，必须自行把关
    if (cmd.sqlScript().contains(";") && cmd.sqlScript().split(";").length > 1) {
        throw new CustomException(40011, "试跑仅支持单条 SQL，请去掉多余的分号或拆分执行。");
    }
    // 3) 强制 LIMIT，防止结果集过大（平台侧另有 10000 行硬上限）
    String sql = SqlPreviewGuard.appendLimitIfAbsent(cmd.sqlScript(), 100);

    PreviewVO preview = baseDataService.sqlPreview(
            cmd.datasourceId(), ds.getDatasourceType(), null, sql);

    // 4) 裁剪：最多 20 行 × 30 列，超长值截断 200 字符
    return McpToolSupport.trimPreview(preview, 20, 30, 200);
}
```

**为什么不能只依赖 `JdbcStatement.validateSqlSafety`**（`JdbcStatement` 第 426-468 行）：

- 多语句分支只执行 `log.warn`，**不抛异常**；
- 真正抛错的是 `xp_cmdshell` / `into outfile` / `load_file` / `exec xp_` 等 6 类模式与 SQL 长度 > 50000；
- 因此「AI 只能跑 SELECT」这条红线必须由工具层的 `previewCheckSql` 承担。

### 7.4 发布与上下线

```java
// 发布：非草稿路径只需 apiId（其余字段取自库中记录）
ApiInfoParam p = new ApiInfoParam();
BaseApiInfo b = new BaseApiInfo();
b.setApiId(cmd.apiId());
p.setBaseInfo(b);
apiInfoService.apiPublish(p);      // 内部置 api_status=release、enabled=1，并失效 API 缓存
```

- **发布即上线**：不需要再调 `set_enabled(1)`。
- 已发布 + 有草稿：`apiPublish` 会发布 `draft_content` 并清空草稿（`publishDraft` 分支）。
- `apigo_api_set_enabled` 仅传 `enabled`，`status` 传 `null`（`updateApiState(apiId, null, enabled)`），不会误改 `api_status`。

### 7.5 授权（`apigo_app_authorize`）

```java
private Map<String, Object> doAuthorize(Long appId, List<Long> apiIds, boolean replaceAll) {
    List<Long> finalIds;
    if (replaceAll) {
        finalIds = apiIds == null ? List.of() : apiIds;
    } else {
        // 默认增量语义：与已有授权合并，避免误清空其它接口的授权
        Set<Long> merged = new LinkedHashSet<>();
        PageInfo<?> exists = apiInfoService.getChooseApi(appId, 1, 1000);  // ApiComboBoxVO
        merged.addAll(extractApiIds(exists));
        if (apiIds != null) merged.addAll(apiIds);
        finalIds = new ArrayList<>(merged);
    }
    BaseAppApi param = new BaseAppApi();
    param.setAppId(appId);
    param.setApiIds(finalIds);
    apiInfoService.addChooseApi(param);   // 内部：按 (appId, 当前userId) 先删后插 + apiInfoCache.invalidateAll()
    return Map.of("appId", appId, "authorizedApiIds", finalIds, "mode", replaceAll ? "replace" : "merge");
}
```

- 底层 `addChooseApi` 是**全量覆盖**（第 383 行 `delete(appId, userId)` + 批量 `insert`），且删除条件带 `create_by`。
- 工具层默认做**合并**语义，把「覆盖」作为显式选项，规避 AI 误清空授权。
- 调用后 `apiInfoCache.invalidateAll()` 已由 service 完成，无需工具层处理。

### 7.6 日志查询与统计

```java
ApiLogParam p = new ApiLogParam();
p.setKeyword(cmd.keyword());
p.setAppName(cmd.appName());
p.setResult(cmd.result());          // success | fail
p.setStartTime(cmd.startTime());    // 必须与 endTime 成对
p.setEndTime(cmd.endTime());
p.setPageNum(support.pageNum(cmd.pageNum()));
p.setPageSize(support.pageSize(cmd.pageSize()));   // 钳制到 ≤20
PageInfo page = logService.page(p);
return McpToolSupport.trimLogPage(page);
```

---

## 8. 端到端对话流

### 8.1 用例一：新建接口并发布（含 SQL 试跑）

```
用户：连一下测试库 192.168.1.10:3306 的 orders 库（账号 readonly/Read@123），
      写个接口查最近 7 天每日订单量，发布到「订单」分组。

AI 调用链：
  ① apigo_group_list()
       → [{groupId:3, groupName:"订单"}]
  ② apigo_datasource_create(datasourceName="orders测试库", datasourceType="mysql",
        host="192.168.1.10", port=3306, databaseName="orders",
        username="readonly", password="Read@123")
       → 工具层拼 jdbcUrl、Base64 编码密码、先 test 后 add → {testPassed:true}
  ③ apigo_datasource_list(keyword="orders")
       → [{datasourceId:3, datasourceType:"mysql", ...}]
  ④ apigo_metadata_tables(datasourceId="3", schema="orders")
       → [{tableName:"t_order", remarks:"订单表"}]
  ⑤ apigo_metadata_columns(datasourceId="3", schema="orders", table="t_order")
       → [{columnName:"create_time", columnType:"datetime", ...}, ...]
  ⑥ apigo_sql_preview(datasourceId="3",
        sqlScript="SELECT DATE(create_time) d, COUNT(*) c FROM t_order
                   WHERE create_time >= DATE_SUB(CURDATE(), INTERVAL 7 DAY) GROUP BY d")
       → {metadata:["d","c"], data:[{d:"2026-09-09", c:128}, ... 7 行]}
  ⑦ apigo_api_create(apiName="近7天每日订单量", apiPath="order/daily/stats",
        apiMethod="GET", datasourceId="3", groupId=3,
        sqlScript="<同 ⑥>", authType="APP_CODE")
       → {apiId:57, apiPath:"/api/web/order/daily/stats", apiStatus:"edit"}
  ⑧ apigo_api_publish(apiId=57)
       → {apiStatus:"release", enabled:1}

AI 回复：接口已创建并上线：GET /api/web/order/daily/stats（apiId=57），
        归属「订单」分组，认证方式 APP_CODE。
```

### 8.2 用例二：授权 + 查日志

```
用户：给「订单小程序」这个应用授权刚建的接口，然后拉一下这条链路的调用日志。

AI 调用链：
  ① apigo_app_list(keyword="订单小程序")
       → [{appId:8, appName:"订单小程序", appCode:"a1b2...", appKey:"f9e8..."}]
  ② apigo_app_authorize(appId=8, apiIds=[57])       # 默认合并语义，不清空其它授权
       → {appId:8, authorizedApiIds:[12, 31, 57], mode:"merge"}
  ③ apigo_log_query(keyword="order/daily/stats", startTime="2026-09-15 00:00:00",
        endTime="2026-09-15 23:59:59")
       → {total:0, list:[]}
  ④ apigo_log_query(keyword="order/daily/stats")     # 去掉时间范围重试
       → {total:12, list:[{requestStatus:"success", costTime:15, appName:"订单小程序", ...}]}

AI 回复：「订单小程序」(appId=8) 已授权接口 57，当前共 3 个接口授权。
        近 7 天该接口调用 12 次，全部成功，平均耗时 15ms。
```

> 提示：用例 ② 若无合适应用，AI 应先调 `apigo_app_create`（工具层用 UUID 生成 appCode/appKey/appSecret 并回显）。

---

## 9. 错误处理与返回规范

### 9.1 Spring AI 2.0 异常契约（已核实）

| 抛出类型 | 行为 |
|---|---|
| `RuntimeException`（含 `CustomException` / `IllegalArgumentException`） | 转为 `isError=true` 的 `CallToolResult`，**错误消息回传模型**，模型可自纠重试 |
| 声明式 checked 异常 | 包装为 `UndeclaredThrowableException` **硬失败**（工具调用整体失败，模型看不到可操作信息） |
| `Error` / `McpError` | 原样抛出（硬失败） |

因此：**工具方法一律不声明 checked 异常**，失败统一抛 `CustomException`（业务）或 `IllegalArgumentException`（参数）。

### 9.2 错误语义化示例

| 场景 | 抛出的异常 | 消息（面向模型） |
|---|---|---|
| Token 缺失/错误 | —— | Filter 返回 HTTP 401，不进入 MCP 层 |
| `datasourceId` 不存在 | `CustomException(51001)` | 数据源不存在！可调用 apigo_datasource_list 获取有效列表 |
| 数据源连不通 | `CustomException(44003)` | 数据源连接测试失败：`<驱动原文>`，请检查地址/账号/网络 |
| `apiPath` 冲突 | `CustomException(50011)` | 接口地址已存在：GET order/daily/stats，请更换 apiPath 或先查询 |
| API 不存在 | `CustomException(44001)`（service 原生） | 无效的API |
| 试跑非 SELECT | `CustomException(40011)` | 试跑仅允许 SELECT 语句 |
| 必填参数缺失 | `IllegalArgumentException` | 必填参数不能为空：datasourceId |

### 9.3 返回体规范

- **成功**：直接返回精简 `Map`（或 record）。不要包 `Result`（`code/msg/data` 与 `isError` 双重语义会让模型困惑）。
- **字段裁剪**：所有返回字段必须显式列举；禁止直接返回 service 的原始实体（会带上 `createBy/createTime/tenantId` 等噪音，且 `BaseDatasource` 的 `password` 在 `getDataSource` 路径下是**真实密码**）。
- **序列化说明**：MCP 结果的 JSON 化由 Spring AI 内部 JsonMapper 完成，**不经过** Spring MVC 的 `spring.jackson.*` 配置。因此 `default-property-inclusion: non_null` 等设置对 MCP 输出无效，裁剪必须在工具层显式完成。

---

## 10. 实施步骤

| 阶段 | 步骤 | 内容 | 门禁 |
|---|---|---|---|
| **P0-1 基座** | 1 | 根 pom（BOM）+ crabc-core pom + `application.yml` | `mvn clean package -DskipTests` 通过 |
| | 2 | `McpConfig` + `McpTokenFilter` + `McpAuthContext` + `McpToolSupport` | 启动无报错；无 token 401 / 有 token 通过 initialize |
| **P0-2 只读工具** | 3 | GroupTools + DataSourceTools（list/test）+ MetaDataTools | Inspector 逐工具调用通过 |
| | 4 | ApiDevTools 的 `sql_parse` / `sql_preview` / `api_search` / `api_detail` | SQL 试跑能拦截非 SELECT |
| **P0-3 写入链路** | 5 | DataSourceTools.create + ApiDevTools.create + ApiOpsTools（publish/set_enabled） | Inspector 走完 建数据源 → 建接口 → 发布 |
| | 6 | AppTools（list/create/authorize）+ LogTools.query + `McpAuditRecorder` | 走完 授权 → 查日志；审计日志可见 |
| **P0-4 验收** | 7 | §8 两个端到端用例 + §11 全量验证 | 全部通过 |
| **P1** | 8 | 9 个 P1 工具（update / rateLimit / api_test / stats 等） | 逐工具验证 |
| **P2（后续版本）** | 9 | `base_mcp_token` 表 + scope/白名单/频控 + 审计落库 + 审批流联动 | 与 BASE-01/BASE-02 同步 |

---

## 11. 验证方案（无测试基建，黑盒验证）

### 11.1 编译与启动

1. `mvn clean package -DskipTests`（需 JDK 21）。
2. 本地 MySQL 执行 `db/mysql.sql`，启动 `AdminApplication`。
3. 观察启动日志：确认 MCP server 启动、27 个工具注册（SYNC 模式下被过滤的方法会有 warn 日志——若出现**非预期**的过滤告警，说明有工具方法签名不符合 SYNC 契约，如返回了 `Mono`/`Flux`）。

### 11.2 鉴权验证（curl）

```bash
# ① 无 token → 401 + WWW-Authenticate
curl -i -X POST http://127.0.0.1:9377/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'

# ② 带 token → 200
curl -i -X POST http://127.0.0.1:9377/mcp \
  -H 'Authorization: Bearer sk-xxxxxxxx' \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
  # 响应头带 Mcp-Session-Id，后续请求需回传该头

# ③ 通知（无响应体）
curl -i -X POST http://127.0.0.1:9377/mcp \
  -H 'Authorization: Bearer sk-xxxxxxxx' -H 'Mcp-Session-Id: <上一步的值>' \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

# ④ 工具列表 → 27 个
curl -s -X POST http://127.0.0.1:9377/mcp \
  -H 'Authorization: Bearer sk-xxxxxxxx' -H 'Mcp-Session-Id: <id>' \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | jq '.result.tools | length'

# ⑤ 调用工具
curl -s -X POST http://127.0.0.1:9377/mcp \
  -H 'Authorization: Bearer sk-xxxxxxxx' -H 'Mcp-Session-Id: <id>' \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"apigo_group_list","arguments":{}}}' | jq

# ⑥ 会话终止（验证 disallow-delete=false 与 Filter 覆盖 DELETE）
curl -i -X DELETE http://127.0.0.1:9377/mcp -H 'Authorization: Bearer sk-xxxxxxxx' -H 'Mcp-Session-Id: <id>'
```

> 断言：无 token 的 POST **与** DELETE 均为 401（验证 Filter 的 `addUrlPatterns("/mcp", "/mcp/*")` 覆盖了全部方法）。

### 11.3 业务链路验证（MCP Inspector）

`npx @modelcontextprotocol/inspector` → Streamable HTTP + Authorization 头，按序调用：

```
apigo_group_list
  → apigo_datasource_create（用一个可连的测试库）→ apigo_datasource_list（取 id）
  → apigo_metadata_schemas / tables / columns
  → apigo_sql_parse（校验参数解析）→ apigo_sql_preview（验证 SELECT-only 拦截：故意传 UPDATE，应报错）
  → apigo_api_create → apigo_api_detail（核对 requestParam/responseParam 已自动生成）
  → apigo_api_publish → apigo_api_set_enabled(0) → apigo_api_set_enabled(1)
  → apigo_app_create → apigo_app_authorize → apigo_app_apis（核对授权结果）
  → apigo_log_query（先无数据，再用已上线接口真实调用一次后复查）
```

### 11.4 数据核对

| 核对项 | 期望 |
|---|---|
| 管理后台接口列表 | 可见 AI 创建的接口，状态 `release`、`enabled=1` |
| `base_api_info.create_by / update_by` | **等于 `crabc.mcp.user-id`**（若为 `0` 说明 §5.2 的绑定失效，必须修复） |
| `base_api_param` | 有 `paramModel=request/response` 的记录（验证 SQL 解析生效） |
| `base_datasource.password` | Base64 字符串，且能正常建池（启动日志无 `Illegal base64` 异常） |
| `base_app_api` | 授权记录数 = 期望集合；重复调用 `authorize` 结果幂等 |
| 审计日志 | `logs/crabc/info.log` 中每个变更工具一条 `[MCP-AUDIT]` |

### 11.5 回归验证

- `/api/box/**`（JWT 控制面）：登录、接口增删改查、发布、授权、日志页行为不变。
- `/api/web/**`（数据面）：已发布接口按 `authType`（NONE/APP_CODE/APP_KEY/APP_SECRET）调用行为不变；`ApiFilter` 日志落库不变。
- CORS：确认 `/mcp` 与 `/api/**` 的跨域行为一致（同一份 `WebConfiguration` 配置）。

### 11.6 客户端兼容性矩阵（建议逐项验证）

| 客户端 | Transport | 预期 | 备注 |
|---|---|---|---|
| MCP Inspector | Streamable HTTP | ✅ | 首选联调工具 |
| WorkBuddy | Streamable HTTP | 待联调 | 确认是否支持自定义 Authorization 头 |
| 千问办公 | Streamable HTTP | 待联调 | 同上；若仅支持 SSE 需双实例 |
| Dify | Streamable HTTP | 待联调 | — |
| 钉钉 AI / 飞书 | Streamable HTTP | 待联调 | 部分平台要求公网 HTTPS |
| Claude Desktop / Cursor | stdio | ❌ 本期不做 | 需独立 stdio 启动器 |

---

## 12. 风险与回退

| # | 风险 | 影响 | 应对 |
|---|---|---|---|
| 1 | Spring AI 2.0.1 与 Boot 4.1.0 自动装配冲突（Jackson 3 / Spring Framework 版本） | 启动失败 | BOM 对齐 Boot 4.x 线；本项目已是 Jackson 3（`tools.jackson`），预期无冲突；若冲突则回退 Boot 补丁版或降 Spring AI 到与 Boot 4.0 对齐的版本 |
| 2 | 客户端只支持旧 SSE | 无法接入 | **`protocol` 三选一不可共存**：需另起一个 `--spring.ai.mcp.server.protocol=SSE` 的实例（同 jar，不同端口/profile）；或升级客户端 |
| 3 | Socket 超时导致长 SQL 工具调用中断 | 工具调用失败 | `request-timeout: 90s`；元数据类工具放慢操作并行度；前端/反代侧同步调大读超时 |
| 4 | 多实例集群与会话态 | 客户端偶发 `session not found` | 有状态 STREAMABLE 需 sticky session；无 sticky 时改 `protocol: STATELESS`（**注意**：此时带 `McpSyncRequestContext` 参数的工具方法会被过滤，本方案未使用该参数，可平滑切换） |
| 5 | 虚拟线程下 ThreadLocal 串号 | 归属用户错乱 | `bindUser` 的 `finally` 强制 `remove()`；`McpTokenFilter` 同样在 `finally` 清理 `McpAuthContext` |
| 6 | 异步分派导致上下文不可见 | `createBy=0`、授权覆盖失效 | §5.2 工具层显式绑定（已规避）；验收项 §11.4 强制核对 `create_by` |
| 7 | AI 误清空应用授权 | 线上接口集体 401 | `apigo_app_authorize` 默认合并语义 + description 强调；覆盖需显式 `replaceAll=true` |
| 8 | AI 误发布 | 线上变更 | `publish` 的 description 强制复述确认；阶段二叠加审批流闸门 |
| 9 | 数据源密码进入模型上下文 | 凭据泄露 | 全路径脱敏；`datasource_get` 强制置空；接入文档建议使用**只读账号** |
| 10 | `/mcp` 路由冲突 | 端点不可用 | 已确认现有前缀为 `/api/box`、`/api/web`，且 `WebConfiguration` 的 404 兜底为 `/index.html`，无冲突 |
| 11 | 工具数量多导致 `tools/list` 上下文占用 | 客户端 token 浪费 | 27 个工具的 description 精简；P1 工具可按需用 `crabc.mcp` 开关裁剪（预留 `tool-whitelist`） |
| **回退** | — | — | ① 快速：`spring.ai.mcp.server.enabled=false` + `crabc.mcp.enabled=false`（进程级关闭）；② 彻底：移除 starter 依赖 + 删除 `cn.crabc.core.app.mcp` 包 + 还原 2 个 pom 与 1 个 yml 增量。**完全可回退、零侵入** |

---

## 13. 交付物清单

**修改 3 个文件**

- `pom.xml`（根）：`spring-ai.version` 属性 + `spring-ai-bom` import
- `crabc-api/crabc-core/pom.xml`：`spring-ai-starter-mcp-server-webmvc`
- `crabc-api/crabc-admin/src/main/resources/application.yml`：`spring.ai.mcp.*` + `crabc.mcp.*`

**新增 15 个文件**（`cn.crabc.core.app.mcp` 包）

| 类 | 职责 |
|---|---|
| `config/McpConfig` | `McpTokenFilter` 注册（`/mcp`, `/mcp/*`） |
| `auth/McpTokenFilter` | Bearer Token 鉴权、401 返回、上下文写入/清理 |
| `auth/McpAuthContext` | 当前调用的 userId / tokenName / scope |
| `support/McpToolSupport` | `bindUser` 包装、分页钳制、文本截断、脱敏、Base64、JDBC URL 模板 |
| `support/McpAuditRecorder` | 变更类工具审计挂载点 |
| `support/SqlPreviewGuard` | SQL 试跑白名单与强制 LIMIT |
| `tools/GroupTools` | 2 个工具 |
| `tools/DataSourceTools` | 5 个工具 |
| `tools/MetaDataTools` | 3 个工具 |
| `tools/ApiDevTools` | 6 个工具 |
| `tools/ApiOpsTools` | 4 个工具 |
| `tools/AppTools` | 4 个工具 |
| `tools/LogTools` | 3 个工具 |

**不动**：任何现有 Controller / Service / Mapper / 前端（`InterceptorConfig`、`WebConfiguration` 均无需改动）

---

## 14. 附录 A：现有代码约束清单（实现时逐条对照）

| # | 约束 | 证据 | 对 MCP 的影响 |
|---|---|---|---|
| 1 | 数据源 `password` 用 **Base64**（非加密）；`addDataSource` **不编码**，但 `addCache` 会 `Base64.decode` | `BaseDataSourceServiceImpl` 第 103-117、134-141、165-177 行；前端 `DataSourceEdit.vue` 第 186-200 行 | 工具层必须 `Base64.encode(password)`，否则 `addDataSource` 抛异常 |
| 2 | 平台只认 `jdbcUrl`，不按 host/port 拼接 | `DefaultDataSourceDriver.createHikariDataSource` 第 118-122 行 | 工具层需内置类型模板拼 URL |
| 3 | `getDataSource(id)` 返回**真实密码** | `BaseDataSourceMapper.xml` `selectOne`（未脱敏） | `apigo_datasource_get` 必须置空 `password` |
| 4 | `getDataSourceList/page` 的 SQL 自带 `'******' as password` | `BaseDataSourceMapper.xml` 第 39 行 | 列表路径无需额外处理 |
| 5 | `addDataSource` 设置 `classify="jdbc"`；`datasourceType=custom` 时从 `jdbcUrl` 反推类型 | `BaseDataSourceServiceImpl` 第 104-113 行 | `custom` 必须传完整 `jdbcUrl` |
| 6 | `apiPath` 归一化：`normalizeApiPath` 去掉前导 `/` | `IBaseApiInfoService` 第 24-29 行 | 工具层复用该静态方法 |
| 7 | `checkApiPath` 返回 `true` 表示**已存在** | `BaseApiInfoServiceImpl` 第 148-151 行 | 语义易反，工具层必须按此判断 |
| 8 | `addApiInfo` 强制 `apiStatus=edit`、`enabled=0`、`apiType=SQL`；`groupId` 为空则置 1 | 第 231-254 行 | 创建后必然未上线，须再发布 |
| 9 | `updateApiInfo`：已发布接口写入 `draft_content` 且**不改变线上内容**；未发布接口会重置 `apiStatus=edit`、`enabled=0` | 第 258-289 行 | 编辑已发布接口后需再次 `publish` 才生效 |
| 10 | `apiPublish` 非草稿路径只用 `apiId`；**发布即上线** | 第 324-361 行（`setEnabled(1)` 第 337 行） | 不需要额外的 `set_enabled(1)` |
| 11 | `getApiInfo(apiId)`：已发布 + 有草稿时返回**草稿内容**，并置 `hasDraft=true` | 第 200-227 行 | `api_detail` 需回传 `hasDraft` 提示模型 |
| 12 | `getApiPage` 返回的 `datasourceName` 恒为 `null`（SQL 无该列） | `BaseApiInfoMapper.xml` 第 7-43 行 | 不要向模型承诺该字段 |
| 13 | `getApiPage` 的 `devType` 实为 `api_status` 等值过滤 | `BaseApiInfoMapper.xml` 第 38-40 行 | description 中改名为 `status` 并给枚举值 |
| 14 | 元数据 CATALOG 分支：`mysql/mariadb/doris/starrocks/tidb/tdsql/sybase` 走 `getCatalogs`，且 `schema` 位置传 catalog | `MetaDataController` 第 51-93 行；`BaseConstant` 第 89 行 | 工具层需按类型切换调用参数位置 |
| 15 | `DataSourceManager.getMetaData(datasourceId)` 不按类型分发，仅校验池是否存在 | `DataSourceManager` 第 63-83 行 | 数据源必须已建池（新增后立即可用） |
| 16 | `addChooseApi` 为**全量覆盖**（先按 `(appId, create_by)` 删再插） | `BaseApiInfoServiceImpl` 第 379-401 行；`BaseAppApiMapper.xml` 第 15-17 行 | 授权语义与 `user-id` 一致性要求见 §6.7 |
| 17 | `addApp` 不生成 `appCode/appKey/appSecret`；强制 `strategyType="white"`、`enabled=1`；`appSecret` **明文存储** | `BaseAppServiceImpl` 第 47-54 行；前端 `ConsumerPopup.vue` 第 88-97 行用 `uuidv4()` 生成 | 工具层用 UUID 生成三件套；秘钥脱敏责任在工具层 |
| 18 | `ApiLogParam` 仅支持 `result/keyword/appName/startTime/endTime` 过滤，**无 apiId/耗时/IP**；时间必须成对 | `ApiLogParam` 全文；`BaseApiLogMapper.xml` 第 29-50 行 | 写进工具 description，避免 AI 试错 |
| 19 | `UserThreadLocal` 存 `Map<String,Object>`；`getUserId()` 在缺 `userId` key 时 **NPE**；`isAdmin/isSuperAdmin` 依赖从未写入的 `role`，调用即 NPE | `UserThreadLocal` 第 37-53 行 | 绑定必须含 `userId`；禁用 `isAdmin/isSuperAdmin` |
| 20 | `UserThreadLocal.getUserId()` 返回 **String** | 同上 | `createBy` 为 String，绑定值给 String 最稳（给 Integer 也能靠 `toString()` 工作） |
| 21 | `IBaseDataService.execute` 接口声明的形参顺序（`datasourceId, schema, datasourceType, ...`）与实现 `BaseDataServiceImpl.execute(datasourceId, datasourceType, schema, ...)` **不一致**；所有调用方按实现顺序传参 | `IBaseDataService` 第 40 行 vs `BaseDataServiceImpl` 第 61 行；`ApiServiceController` 第 233 行、`ApiTestController` 第 70 行 | 工具层调用必须用**实现顺序**：`(datasourceId, datasourceType, schema, sql, params)`；建议另开小 PR 修正接口签名注释，避免后人踩坑 |
| 22 | `sqlPreview` 形参顺序为 `(datasourceId, datasourceType, schema, sql)` | `IBaseDataService` 第 29 行；`ApiTestController` 第 45 行 | 与实现一致，可直接用 |
| 23 | `JdbcStatement.validateSqlSafety`：多语句仅 `log.warn`；真正拦截 `xp_cmdshell`/`into outfile`/`into dumpfile`/`load_file`/`exec xp_`/`exec sp_` 与 SQL > 50000 字符 | `JdbcStatement` 第 426-468 行 | 「仅 SELECT」红线必须由工具层 `previewCheckSql` 承担 |
| 24 | `SQLUtil.previewCheckSql(sql, dbType)` 提供 SELECT-only 判定（现无调用方，可直接复用） | `SQLUtil` 第 429-441 行 | `apigo_sql_preview` 的第一道闸门 |
| 25 | 前端置 `spring.threads.virtual.enabled: true`（虚拟线程） | `application.yml` 第 14-16 行 | ThreadLocal 必须及时 `remove()` |
| 26 | `logback.xml` 未配 MDC（pattern 无 `%X{}`） | `logback.xml` 第 5 行 | 审计字段写在消息体内，便于 grep |
| 27 | CORS 已覆盖 `/**`，`allowedHeaders("*")` | `WebConfiguration` 第 38-45 行 | `/mcp` 无需额外 CORS 配置 |
| 28 | 仅一个 `application.yml`，无 profile 拆分配置 | 全仓扫描 | 双协议（STREAMABLE + SSE）需靠启动参数 `--spring.ai.mcp.server.protocol=` 区分 |
| 29 | 无任何测试代码；`crabc-core` 已声明 `spring-boot-starter-test`（test scope）但无测试 | 全仓扫描 | 验证为黑盒；如需补测试，基础设施已具备 |
| 30 | `ApiStateEnum.AUDIT` 为**遗留定义**，`apiPublish` 中无任何审批引用 | `ApiStateEnum` 第 8-13 行；`BaseApiInfoServiceImpl` 第 324-361 行 | 本期不做审批闸门，AI 发布即生效 |

---

## 15. 附录 B：与《功能迭代需求文档》的差异仲裁

需求文档（`docs/ApiGo功能迭代需求文档与开发计划.md`）AI-01~AI-04 描述的是一套完整产品级 MCP 能力（自研 JSON-RPC、三 transport、Token 表、审批联动、审计落库）。本方案是其**可快速落地的子集**，差异与理由如下：

| 维度 | 需求文档 | 本方案 | 理由 |
|---|---|---|---|
| 实现方式 | 主路径为**自研** JSON-RPC（4 个方法），Spring AI 为备选 PoC | **选定 Spring AI 2.0.1 starter**（方案 A） | Spring AI 2.0.1 已 GA，与 Boot 4.1/JDK 21/Jackson 3 完全对齐；自研需手写会话、Schema 生成、SSE 与协议演进兼容，性价比低。建议将需求文档"风险与应对"表中该条更新为：已核实 Spring AI 2.0.1 兼容，主路径改为 Spring AI |
| 工具数量 | `tools: 20+`，含 `delete_datasource` / `destroy_api` | **27 个，不含删除类** | 删除类无二次确认机制时不安全；先跑通正向全流程，删除留待有 confirm/审批闸门后开放 |
| 传输 | 三 transport 全覆盖（STREAMABLE + SSE + stdio） | **仅 STREAMABLE** | 目标平台为远程 SaaS；`protocol` 不可共存，SSE 可用同 jar 另一实例满足；stdio 面向本地客户端，不在本次目标 |
| 鉴权 | `base_mcp_token` 表（摘要存储 / scope / 白名单 / 频控 / 绑定用户） | **阶段一 yml 静态 Token**，阶段二上表 | 阶段一 0 表结构、0 前端改动即可交付；token 表与 BASE-02 审计同步建设更合理 |
| 治理 | confirm 参数 + 审批流闸门 + 审计落库 + 频控 | 工具 description 强制复述 + 审计日志挂载点（不落库） | BASE-01/BASE-02 尚未实现；本方案预留 `McpAuditRecorder` 与 `confirm` 语义，底座就绪后接口不变、直接切换 |
| SQL 试跑 | `/sys/test/running`，SELECT 白名单 + 强制 LIMIT | 一致，且复用现存未使用的 `SQLUtil.previewCheckSql` | — |

> 结论：本方案与需求文档的长期目标不冲突，是其 **P0 子集 + 明确演进路径**。需求文档中 AI-01~AI-04 的排期与人力估算可相应下调（10+12+8+6 人日 → 本方案约 **8~10 人日** 可交付 P0 全流程）。
