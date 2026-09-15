package cn.crabc.core.app.mcp.tools;

import cn.crabc.core.app.entity.BaseApiInfo;
import cn.crabc.core.app.entity.BaseApiParam;
import cn.crabc.core.app.entity.BaseApiSql;
import cn.crabc.core.app.entity.BaseDatasource;
import cn.crabc.core.app.entity.param.ApiInfoParam;
import cn.crabc.core.app.entity.vo.ApiInfoVO;
import cn.crabc.core.app.entity.vo.ColumnParseVo;
import cn.crabc.core.app.entity.vo.SqlParseVO;
import cn.crabc.core.app.mcp.support.McpToolSupport;
import cn.crabc.core.app.mcp.support.SqlPreviewGuard;
import cn.crabc.core.app.service.core.IBaseDataService;
import cn.crabc.core.app.service.system.IBaseApiInfoService;
import cn.crabc.core.app.service.system.IBaseDataSourceService;
import cn.crabc.core.app.util.SQLUtil;
import cn.crabc.core.datasource.constant.BaseConstant;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import cn.crabc.core.datasource.util.PageInfo;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * MCP 接口开发工具：SQL 解析/试跑、接口查询/详情、创建/编辑。
 *
 * @author yuqf
 */
@Component
public class ApiDevTools {

    /**
     * 试跑返回给模型的最大行数/列数。
     */
    private static final int PREVIEW_MAX_ROWS = 20;
    private static final int PREVIEW_MAX_COLUMNS = 30;
    private static final int PREVIEW_MAX_VALUE_LENGTH = 200;

    /**
     * 与前端 TabSql.vue#handleRequestParam 保持一致的参数类型推断。
     */
    private static final Pattern INT_SUFFIX = Pattern.compile("(id|_id|Id|num|_num|Num)$");

    /**
     * 接口字段白名单：避免 AI 写入非法枚举值导致运行期异常。
     */
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "DELETE");
    private static final Set<String> ALLOWED_AUTH_TYPES = Set.of("NONE", "APP_CODE", "APP_KEY", "APP_SECRET");
    private static final Set<String> ALLOWED_RESULT_TYPES = Set.of("ONE", "ARRAY", "EXCEL");
    private static final Set<Integer> ALLOWED_PAGE_SETUP = Set.of(0, 1, 2);

    private final IBaseApiInfoService apiInfoService;
    private final IBaseDataSourceService dataSourceService;
    private final IBaseDataService baseDataService;
    private final McpToolSupport support;
    private final JsonMapper jsonMapper;

    public ApiDevTools(IBaseApiInfoService apiInfoService,
                       IBaseDataSourceService dataSourceService,
                       IBaseDataService baseDataService,
                       McpToolSupport support,
                       JsonMapper jsonMapper) {
        this.apiInfoService = apiInfoService;
        this.dataSourceService = dataSourceService;
        this.baseDataService = baseDataService;
        this.support = support;
        this.jsonMapper = jsonMapper;
    }

    @McpTool(name = "apigo_sql_parse",
            description = """
                    解析 SQL 脚本，得到请求参数名与返回列名（不执行 SQL、无副作用）。
                    用途：写 SQL 后先自检 #{参数} 是否被正确识别、SELECT 列是否齐全。
                    注意：仅静态解析，无法校验表名/字段是否真实存在，需用 apigo_metadata_columns 确认。
                    """,
            annotations = @McpTool.McpAnnotations(title = "解析 SQL 参数", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> sqlParse(
            @McpToolParam(description = "SQL 脚本，支持 MyBatis 动态标签", required = true) String sqlScript,
            @McpToolParam(description = "数据源类型，如 mysql/postgresql，缺省按 mysql 解析", required = false) String datasourceType) {
        return support.bindUser("apigo_sql_parse", () -> {
            String sql = McpToolSupport.stripScriptTags(support.requireText(sqlScript, "sqlScript"));
            SqlParseVO parsed = parseSql(sql, datasourceType);
            Map<String, Object> output = McpToolSupport.newResult();
            output.put("requestParamNames", new ArrayList<>(new TreeSet<>(parsed.getReqColumns())));
            List<Map<String, Object>> columns = new ArrayList<>();
            for (ColumnParseVo column : sortColumns(parsed.getResColumns())) {
                String[] parts = splitColumn(column.getColName());
                Map<String, Object> item = McpToolSupport.newResult();
                McpToolSupport.putIfNotNull(item, "columnName", parts[0]);
                McpToolSupport.putIfNotNull(item, "alias", parts[1]);
                columns.add(item);
            }
            output.put("resultColumns", columns);
            if (columns.isEmpty()) {
                output.put("tip", "未解析出返回列，可能是 select * 或 SQL 复杂度过高，建议显式列出字段");
            }
            return output;
        });
    }

    @McpTool(name = "apigo_sql_preview",
            description = """
                    试跑 SELECT 语句并返回少量样例数据（最多 20 行），用于验证 SQL 正确性与结果形态。
                    安全约束：仅允许单条 SELECT 语句，DML/DDL 会被拒绝；平台侧另有 1000 行硬上限。
                    失败语义：表不存在/字段错误/占位符缺参会返回数据库原始错误，可据此修正后重试。
                    参数：#{param} 占位符的值可通过 paramsJson 传入，如 {"id": 1}。
                    """,
            annotations = @McpTool.McpAnnotations(title = "试跑 SQL", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> sqlPreview(
            @McpToolParam(description = "数据源ID", required = true) String datasourceId,
            @McpToolParam(description = "单条 SELECT 语句", required = true) String sqlScript,
            @McpToolParam(description = "SQL 占位符取值的 JSON 对象字符串，可选", required = false) String paramsJson,
            @McpToolParam(description = "库/schema，可选", required = false) String schema) {
        return support.bindUser("apigo_sql_preview", () -> {
            String id = support.requireText(datasourceId, "datasourceId");
            String sql = McpToolSupport.stripScriptTags(support.requireText(sqlScript, "sqlScript"));
            BaseDatasource dataSource = requireDatasource(id);
            String datasourceType = dataSource.getDatasourceType();

            SqlPreviewGuard.validatePreviewSql(sql, datasourceType);
            String limitedSql = SqlPreviewGuard.appendLimitIfAbsent(sql, SqlPreviewGuard.DEFAULT_PREVIEW_LIMIT);

            // 参数顺序为 BaseDataServiceImpl 的实现顺序：(datasourceId, datasourceType, schema, sql, params)
            Map<String, Object> params = new HashMap<>();
            params.putAll(McpToolSupport.parseParamsJson(jsonMapper, paramsJson, "paramsJson"));
            // 系统参数后置，避免被用户入参覆盖
            params.put(BaseConstant.DATA_SOURCE_TYPE, datasourceType);
            params.put(BaseConstant.BASE_API_EXEC_TYPE, "preview");
            params.put(BaseConstant.PAGE_SETUP, BaseConstant.PAGE_ONLY);

            Object result = baseDataService.execute(id, datasourceType, schema, limitedSql, params);
            return toPreviewOutput(result);
        });
    }

    @McpTool(name = "apigo_api_search",
            description = """
                    分页查询接口列表，返回 apiId、名称、路径、方法、状态、是否上线、分组名。
                    用途：确认接口是否已存在、获取 apiId 供后续发布/详情/授权使用。
                    keyword 同时匹配接口名称与路径；status 为接口状态（edit=草稿、release=已发布）。
                    注意：返回结果不含数据源名称，需要时请用 apigo_api_detail。
                    """,
            annotations = @McpTool.McpAnnotations(title = "查询接口列表", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> apiSearch(
            @McpToolParam(description = "接口名称或路径关键字", required = false) String keyword,
            @McpToolParam(description = "接口状态：edit（草稿）/ release（已发布）", required = false) String status,
            @McpToolParam(description = "页码，从 1 开始", required = false) Integer pageNum,
            @McpToolParam(description = "每页条数，默认 10，上限 20", required = false) Integer pageSize) {
        return support.bindUser("apigo_api_search", () -> {
            int num = support.pageNum(pageNum);
            int size = support.pageSize(pageSize);
            PageInfo<BaseApiInfo> page = apiInfoService.getApiPage(keyword, status, num, size);

            List<Map<String, Object>> list = new ArrayList<>();
            if (page != null && page.getList() != null) {
                for (BaseApiInfo api : page.getList()) {
                    Map<String, Object> item = McpToolSupport.newResult();
                    McpToolSupport.putIfNotNull(item, "apiId", api.getApiId());
                    McpToolSupport.putIfNotNull(item, "apiName", api.getApiName());
                    McpToolSupport.putIfNotNull(item, "apiPath", api.getApiPath());
                    McpToolSupport.putIfNotNull(item, "apiMethod", api.getApiMethod());
                    McpToolSupport.putIfNotNull(item, "apiStatus", api.getApiStatus());
                    McpToolSupport.putIfNotNull(item, "enabled", api.getEnabled());
                    McpToolSupport.putIfNotNull(item, "groupName", api.getGroupName());
                    list.add(item);
                }
            }
            Map<String, Object> output = McpToolSupport.newResult();
            output.put("total", page == null ? 0L : page.getTotal());
            output.put("pageNum", num);
            output.put("pageSize", size);
            output.put("list", list);
            return output;
        });
    }

    @McpTool(name = "apigo_api_detail",
            description = """
                    查询接口完整详情：基本信息、SQL 脚本、请求参数与返回参数定义。
                    注意 hasDraft=true 表示该已发布接口存在未发布的暂存内容，此时返回的是暂存内容。
                    """,
            annotations = @McpTool.McpAnnotations(title = "查询接口详情", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> apiDetail(
            @McpToolParam(description = "接口ID", required = true) Long apiId) {
        return support.bindUser("apigo_api_detail", () -> {
            ApiInfoVO detail = apiInfoService.getApiInfo(apiId);
            if (detail == null || detail.getBaseInfo() == null || detail.getBaseInfo().getApiId() == null) {
                throw new CustomException(ErrorStatusEnum.API_NOT_FOUNT.getCode(), "无效的API：" + apiId);
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("baseInfo", toBaseInfo(detail.getBaseInfo()));
            BaseApiSql sqlInfo = detail.getSqlInfo() == null ? new BaseApiSql() : detail.getSqlInfo();
            Map<String, Object> sql = McpToolSupport.newResult();
            McpToolSupport.putIfNotNull(sql, "datasourceId", sqlInfo.getDatasourceId());
            McpToolSupport.putIfNotNull(sql, "datasourceType", sqlInfo.getDatasourceType());
            McpToolSupport.putIfNotNull(sql, "schemaName", sqlInfo.getSchemaName());
            McpToolSupport.putIfNotNull(sql, "tableName", sqlInfo.getTableName());
            McpToolSupport.putIfNotNull(sql, "pageSetup", sqlInfo.getPageSetup());
            sql.put("sqlScript", support.truncateText(detail.getBaseInfo().getSqlScript() != null
                    ? detail.getBaseInfo().getSqlScript() : sqlInfo.getSqlScript()));
            output.put("sqlInfo", sql);
            output.put("requestParam", toParamList(detail.getRequestParam()));
            output.put("responseParam", toParamList(detail.getResponseParam()));
            output.put("hasDraft", Boolean.TRUE.equals(detail.getHasDraft()));
            return output;
        });
    }

    @McpTool(name = "apigo_api_create",
            description = """
                    创建一个 SQL 接口，保存为草稿状态（edit / 未上线），需再调用 apigo_api_publish 发布上线。
                    执行顺序：校验 apiPath 唯一性 → 校验 SQL 类型 → 解析 SQL 自动生成请求/返回参数 → 保存。
                    前置条件：datasourceId 来自 apigo_datasource_list，建议先用 apigo_sql_preview 验证 SQL。
                    失败语义：apiPath + apiMethod 已存在时直接报错，可更换 apiPath 或先查询已有接口。
                    apiPath 不要以 / 开头（如 order/daily/stats），调用地址为 /api/web/ + apiPath。
                    重要：若 SQL 是变更类语句（INSERT/UPDATE/DELETE/DDL 等），必须先向用户复述其影响
                    并取得确认，再以 allowDml=true 重新调用；否则会被拒绝。
                    """,
            annotations = @McpTool.McpAnnotations(title = "创建接口", readOnlyHint = false,
                    destructiveHint = true, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> apiCreate(
            @McpToolParam(description = "接口名称", required = true) String apiName,
            @McpToolParam(description = "接口路径，不以 / 开头，如 order/daily/stats", required = true) String apiPath,
            @McpToolParam(description = "数据源ID", required = true) String datasourceId,
            @McpToolParam(description = "SQL 脚本，支持 MyBatis 动态标签与 #{参数}", required = true) String sqlScript,
            @McpToolParam(description = "HTTP 方法：GET/POST/PUT/DELETE，默认 GET", required = false) String apiMethod,
            @McpToolParam(description = "分组ID，缺省为默认分组 1", required = false) Integer groupId,
            @McpToolParam(description = "认证方式：NONE / APP_CODE / APP_KEY / APP_SECRET，默认 NONE", required = false) String authType,
            @McpToolParam(description = "分页设置：0 不分页，1 分页，2 分页并统计，默认 0", required = false) Integer pageSetup,
            @McpToolParam(description = "返回类型：one / array / excel，默认 array", required = false) String resultType,
            @McpToolParam(description = "库/schema，可选", required = false) String schemaName,
            @McpToolParam(description = "表名，可选", required = false) String tableName,
            @McpToolParam(description = "接口描述", required = false) String remarks,
            @McpToolParam(description = "确认创建变更类（DML/DDL）接口：取得用户确认后传 true", required = false) Boolean allowDml) {
        return support.bindWrite("apigo_api_create", "apiPath=" + apiPath, () -> {
            String name = support.requireText(apiName, "apiName");
            String path = IBaseApiInfoService.normalizeApiPath(support.requireText(apiPath, "apiPath"));
            String id = support.requireText(datasourceId, "datasourceId");
            String script = McpToolSupport.stripScriptTags(support.requireText(sqlScript, "sqlScript"));
            String method = requireEnum(apiMethod, "apiMethod", ALLOWED_METHODS, "GET");
            String auth = requireEnum(authType, "authType", ALLOWED_AUTH_TYPES, "NONE");
            String resultTypeValue = requireEnum(resultType, "resultType", ALLOWED_RESULT_TYPES, "ARRAY").toLowerCase(Locale.ROOT);
            Integer pageSetupValue = requirePageSetup(pageSetup);

            if (Boolean.TRUE.equals(apiInfoService.checkApiPath(null, path, method))) {
                throw new CustomException(50011, "接口地址已存在：" + method + " " + path
                        + "，可先用 apigo_api_search 查询，或更换 apiPath");
            }
            BaseDatasource dataSource = requireDatasource(id);
            boolean selectOnly = requireSqlTypeConfirmed(script, dataSource.getDatasourceType(), allowDml);

            ApiInfoParam param = new ApiInfoParam();
            BaseApiInfo base = new BaseApiInfo();
            base.setApiName(name);
            base.setApiPath(path);
            base.setApiMethod(method);
            base.setAuthType(auth);
            base.setGroupId(groupId == null ? 1 : groupId);
            base.setPageSetup(pageSetupValue == null ? 0 : pageSetupValue);
            base.setResultType(resultTypeValue);
            base.setRemarks(remarks);
            // apiId 必须为 null，服务层以此判定为新增
            param.setBaseInfo(base);

            BaseApiSql sql = new BaseApiSql();
            sql.setDatasourceId(Integer.valueOf(id));
            sql.setDatasourceType(dataSource.getDatasourceType());
            sql.setSchemaName(isBlank(schemaName) ? "" : schemaName.trim());
            sql.setTableName(isBlank(tableName) ? "" : tableName.trim());
            sql.setSqlScript(script);
            sql.setPageSetup(base.getPageSetup());
            param.setSqlInfo(sql);

            SqlParseVO parsed = parseSql(script, dataSource.getDatasourceType());
            param.setRequestParam(buildRequestParams(parsed.getReqColumns(), id, base.getSchemaName()));
            param.setResponseParam(buildResponseParams(parsed.getResColumns(), id, base.getSchemaName()));

            Long apiId = apiInfoService.addApiInfo(param);

            Map<String, Object> output = McpToolSupport.newResult();
            McpToolSupport.putIfNotNull(output, "apiId", apiId);
            output.put("apiPath", "/api/web/" + path);
            output.put("apiMethod", method);
            output.put("apiStatus", "edit");
            output.put("enabled", 0);
            output.put("mutatingSql", !selectOnly);
            output.put("requestParamNames", new ArrayList<>(new TreeSet<>(parsed.getReqColumns())));
            output.put("responseParamNames", extractAliases(parsed.getResColumns()));
            output.put("tip", selectOnly
                    ? "接口已创建为草稿，需调用 apigo_api_publish 发布上线"
                    : "注意：该接口的 SQL 会变更数据，发布前请再次与用户确认影响范围；"
                    + "接口已创建为草稿，需调用 apigo_api_publish 发布上线");
            return output;
        });
    }

    @McpTool(name = "apigo_api_update",
            description = """
                    编辑已有接口。未传的字段保留原值；传了 sqlScript 会重新解析请求/返回参数。
                    重要：已发布（release）接口的修改会写入暂存内容（草稿），不会影响线上；
                    需再调用 apigo_api_publish 才会让修改生效。
                    草稿（edit）接口的修改会直接生效，但会被重置为未上线状态。
                    """,
            annotations = @McpTool.McpAnnotations(title = "编辑接口", readOnlyHint = false,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> apiUpdate(
            @McpToolParam(description = "接口ID", required = true) Long apiId,
            @McpToolParam(description = "接口名称", required = false) String apiName,
            @McpToolParam(description = "接口路径，不以 / 开头", required = false) String apiPath,
            @McpToolParam(description = "数据源ID", required = false) String datasourceId,
            @McpToolParam(description = "SQL 脚本", required = false) String sqlScript,
            @McpToolParam(description = "HTTP 方法：GET/POST/PUT/DELETE", required = false) String apiMethod,
            @McpToolParam(description = "分组ID", required = false) Integer groupId,
            @McpToolParam(description = "认证方式：NONE / APP_CODE / APP_KEY / APP_SECRET", required = false) String authType,
            @McpToolParam(description = "分页设置：0 / 1 / 2", required = false) Integer pageSetup,
            @McpToolParam(description = "返回类型：one / array / excel", required = false) String resultType,
            @McpToolParam(description = "库/schema", required = false) String schemaName,
            @McpToolParam(description = "表名", required = false) String tableName,
            @McpToolParam(description = "接口描述", required = false) String remarks,
            @McpToolParam(description = "确认将 SQL 改为变更类（DML/DDL）语句：取得用户确认后传 true", required = false) Boolean allowDml) {
        return support.bindWrite("apigo_api_update", "apiId=" + apiId, () -> {
            if (apiId == null) {
                throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), "必传参数不能为空：apiId");
            }
            ApiInfoVO current = apiInfoService.getApiInfo(apiId);
            if (current == null || current.getBaseInfo() == null || current.getBaseInfo().getApiId() == null) {
                throw new CustomException(ErrorStatusEnum.API_NOT_FOUNT.getCode(), "无效的API：" + apiId);
            }
            BaseApiInfo base = current.getBaseInfo();
            BaseApiSql oldSql = current.getSqlInfo() == null ? new BaseApiSql() : current.getSqlInfo();

            // 先做枚举校验，避免把非法值写进库
            String newMethod = isBlank(apiMethod) ? null : requireEnum(apiMethod, "apiMethod", ALLOWED_METHODS, null);
            String newAuth = isBlank(authType) ? null : requireEnum(authType, "authType", ALLOWED_AUTH_TYPES, null);
            String newResultType = isBlank(resultType)
                    ? null : requireEnum(resultType, "resultType", ALLOWED_RESULT_TYPES, null).toLowerCase(Locale.ROOT);
            Integer newPageSetup = requirePageSetup(pageSetup);

            String normalizedScript = McpToolSupport.stripScriptTags(sqlScript);
            boolean sqlChanged = !isBlank(normalizedScript) && !normalizedScript.equals(oldSql.getSqlScript());
            String finalSqlScript = isBlank(normalizedScript) ? oldSql.getSqlScript() : normalizedScript;
            String finalDatasourceId = isBlank(datasourceId)
                    ? (base.getDatasourceId() == null ? null : String.valueOf(base.getDatasourceId()))
                    : datasourceId;
            if (isBlank(finalDatasourceId)) {
                throw new CustomException(ErrorStatusEnum.DATASOURCE_NOT_FOUNT.getCode(), "该接口未关联数据源，请传入 datasourceId");
            }
            BaseDatasource dataSource = requireDatasource(finalDatasourceId);
            boolean selectOnly = !sqlChanged
                    || requireSqlTypeConfirmed(finalSqlScript, dataSource.getDatasourceType(), allowDml);

            if (!isBlank(apiName)) {
                base.setApiName(apiName.trim());
            }
            if (!isBlank(apiPath)) {
                String path = IBaseApiInfoService.normalizeApiPath(apiPath.trim());
                if (!path.equals(base.getApiPath())) {
                    String method = newMethod == null ? base.getApiMethod() : newMethod;
                    if (Boolean.TRUE.equals(apiInfoService.checkApiPath(apiId, path, method))) {
                        throw new CustomException(50011, "接口地址已存在：" + method + " " + path);
                    }
                    base.setApiPath(path);
                }
            }
            if (newMethod != null) {
                base.setApiMethod(newMethod);
            }
            if (newAuth != null) {
                base.setAuthType(newAuth);
            }
            if (groupId != null) {
                base.setGroupId(groupId);
            }
            if (newPageSetup != null) {
                base.setPageSetup(newPageSetup);
            }
            if (newResultType != null) {
                base.setResultType(newResultType);
            }
            if (!isBlank(remarks)) {
                base.setRemarks(remarks);
            }
            base.setDatasourceId(Integer.valueOf(finalDatasourceId));
            base.setDatasourceType(dataSource.getDatasourceType());
            if (!isBlank(schemaName)) {
                base.setSchemaName(schemaName.trim());
            }
            if (!isBlank(tableName)) {
                base.setTableName(tableName.trim());
            }
            base.setSqlScript(finalSqlScript);

            oldSql.setApiId(apiId);
            oldSql.setDatasourceId(Integer.valueOf(finalDatasourceId));
            oldSql.setDatasourceType(dataSource.getDatasourceType());
            oldSql.setSqlScript(finalSqlScript);
            oldSql.setSchemaName(base.getSchemaName());
            oldSql.setTableName(base.getTableName());
            oldSql.setPageSetup(base.getPageSetup());

            ApiInfoParam param = new ApiInfoParam();
            param.setBaseInfo(base);
            param.setSqlInfo(oldSql);

            // 仅在 SQL 真正变化时重新解析参数，否则沿用现有定义，
            // 避免覆盖用户在控制台里对参数类型/是否必填/描述的定制
            List<String> regenerate = new ArrayList<>();
            if (sqlChanged) {
                SqlParseVO parsed = parseSql(finalSqlScript, dataSource.getDatasourceType());
                param.setRequestParam(buildRequestParams(parsed.getReqColumns(), finalDatasourceId, base.getSchemaName()));
                param.setResponseParam(buildResponseParams(parsed.getResColumns(), finalDatasourceId, base.getSchemaName()));
                regenerate.add("requestParam");
                regenerate.add("responseParam");
            } else {
                param.setRequestParam(current.getRequestParam());
                param.setResponseParam(current.getResponseParam());
            }

            apiInfoService.updateApiInfo(param);

            Map<String, Object> output = McpToolSupport.newResult();
            McpToolSupport.putIfNotNull(output, "apiId", apiId);
            McpToolSupport.putIfNotNull(output, "apiPath", base.getApiPath());
            output.put("apiMethod", base.getApiMethod());
            output.put("apiStatus", base.getApiStatus());
            output.put("sqlChanged", sqlChanged);
            output.put("mutatingSql", !selectOnly);
            output.put("regenerated", regenerate);
            if ("release".equals(base.getApiStatus())) {
                output.put("tip", "该接口已发布，本次修改已写入暂存内容，需调用 apigo_api_publish 才会生效");
            } else {
                output.put("tip", "接口为草稿状态，修改已生效但未上线，需调用 apigo_api_publish 上线");
            }
            return output;
        });
    }

    /**
     * 复刻 {@code ApiInfoController#sqlParse} 的解析流程（纯静态工具方法调用，无需改动控制器）。
     */
    private SqlParseVO parseSql(String sqlScript, String datasourceType) {
        String sql = sqlScript;
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.lastIndexOf(";"));
        }

        Set<String> paramNames = new HashSet<>();
        if (sql.contains("</foreach>")) {
            paramNames.addAll(SQLUtil.extractForeachParams(sql));
        }
        if (sql.contains("<if ") || sql.contains("<when ")) {
            paramNames.addAll(SQLUtil.extractIfParams(sql));
        }
        sql = sql.replaceAll("<foreach[\\s\\S]*?</foreach>", "()");
        paramNames.addAll(SQLUtil.parseParams(sql));

        SqlParseVO result = new SqlParseVO();
        result.setReqColumns(paramNames);

        String filtered = SQLUtil.sqlFilter(sql);
        filtered = SQLUtil.completeSql(filtered);
        Set<ColumnParseVo> resColumns = new HashSet<>();
        Set<String> resNames = SQLUtil.parseResultColumns(filtered, datasourceType);
        if (resNames != null) {
            for (String name : resNames) {
                if (!name.startsWith("*")) {
                    resColumns.add(SQLUtil.buildColumnInfo(name));
                }
            }
        }
        result.setResColumns(resColumns);
        return result;
    }

    /**
     * 请求参数组装，字段语义与前端 TabSql.vue 保持一致。
     */
    private List<BaseApiParam> buildRequestParams(Set<String> paramNames, String datasourceId, String schemaName) {
        List<BaseApiParam> params = new ArrayList<>();
        if (paramNames == null) {
            return params;
        }
        // 解析结果为无序集合，排序后落库保证结果稳定可复现
        for (String name : new TreeSet<>(paramNames)) {
            BaseApiParam param = new BaseApiParam();
            param.setParamName(name);
            param.setColumnName("");
            param.setParamModel("request");
            param.setParamType(inferParamType(name));
            param.setRequired("Y");
            param.setOperation("=");
            param.setDatasourceId(Integer.valueOf(datasourceId));
            param.setSchemaName(schemaName);
            params.add(param);
        }
        return params;
    }

    /**
     * 返回参数组装：解析结果形如 "colName,alias"，与前端一致地拆出字段名与别名。
     */
    private List<BaseApiParam> buildResponseParams(Set<ColumnParseVo> resColumns, String datasourceId, String schemaName) {
        List<BaseApiParam> params = new ArrayList<>();
        if (resColumns == null) {
            return params;
        }
        Set<String> exists = new HashSet<>();
        for (ColumnParseVo column : sortColumns(resColumns)) {
            String raw = column.getColName();
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String[] parts = splitColumn(raw);
            if (!exists.add(parts[1])) {
                continue;
            }
            BaseApiParam param = new BaseApiParam();
            param.setParamName(parts[1]);
            param.setColumnName(parts[0]);
            param.setParamModel("response");
            param.setParamType(inferParamType(parts[1]));
            param.setRequired("Y");
            param.setDatasourceId(Integer.valueOf(datasourceId));
            param.setSchemaName(schemaName);
            params.add(param);
        }
        return params;
    }

    private List<String> extractAliases(Set<ColumnParseVo> resColumns) {
        List<String> aliases = new ArrayList<>();
        for (ColumnParseVo column : sortColumns(resColumns)) {
            String alias = splitColumn(column.getColName())[1];
            if (!alias.isBlank() && !aliases.contains(alias)) {
                aliases.add(alias);
            }
        }
        return aliases;
    }

    /**
     * 解析结果是无序集合，按别名排序后输出，保证工具返回与落库顺序稳定可复现。
     */
    private List<ColumnParseVo> sortColumns(Set<ColumnParseVo> resColumns) {
        List<ColumnParseVo> sorted = new ArrayList<>();
        if (resColumns == null) {
            return sorted;
        }
        sorted.addAll(resColumns);
        sorted.sort(Comparator.comparing(column -> splitColumn(column.getColName())[1]));
        return sorted;
    }

    /**
     * 解析结果形如 "columnName,alias"，拆为 {字段名, 别名}；无别名时别名等于字段名。
     */
    private String[] splitColumn(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[]{"", ""};
        }
        String[] parts = raw.split(",", -1);
        String columnName = parts[0];
        String alias = parts.length <= 1 || parts[1].isBlank() ? parts[0] : parts[1];
        return new String[]{columnName, alias};
    }

    /**
     * 参数类型推断，规则与前端 TabSql.vue#handleRequestParam 一致。
     */
    private String inferParamType(String name) {
        if (name == null || name.isBlank()) {
            return "String";
        }
        if (INT_SUFFIX.matcher(name).find()) {
            return "Int";
        }
        if (name.contains("time") || name.endsWith("Time") || name.endsWith("Date") || name.endsWith("_date")) {
            return "Date";
        }
        return "String";
    }

    /**
     * 试跑结果裁剪：限制行数、列数与单值长度，避免模型上下文膨胀。
     */
    private Map<String, Object> toPreviewOutput(Object result) {
        return McpToolSupport.trimQueryResult(result, PREVIEW_MAX_ROWS, PREVIEW_MAX_COLUMNS,
                PREVIEW_MAX_VALUE_LENGTH);
    }

    private Map<String, Object> toBaseInfo(BaseApiInfo base) {
        Map<String, Object> item = McpToolSupport.newResult();
        McpToolSupport.putIfNotNull(item, "apiId", base.getApiId());
        McpToolSupport.putIfNotNull(item, "apiName", base.getApiName());
        McpToolSupport.putIfNotNull(item, "apiPath", base.getApiPath());
        McpToolSupport.putIfNotNull(item, "apiMethod", base.getApiMethod());
        McpToolSupport.putIfNotNull(item, "apiType", base.getApiType());
        McpToolSupport.putIfNotNull(item, "authType", base.getAuthType());
        McpToolSupport.putIfNotNull(item, "apiStatus", base.getApiStatus());
        McpToolSupport.putIfNotNull(item, "enabled", base.getEnabled());
        McpToolSupport.putIfNotNull(item, "groupId", base.getGroupId());
        McpToolSupport.putIfNotNull(item, "groupName", base.getGroupName());
        McpToolSupport.putIfNotNull(item, "datasourceId", base.getDatasourceId());
        McpToolSupport.putIfNotNull(item, "datasourceType", base.getDatasourceType());
        McpToolSupport.putIfNotNull(item, "schemaName", base.getSchemaName());
        McpToolSupport.putIfNotNull(item, "tableName", base.getTableName());
        McpToolSupport.putIfNotNull(item, "pageSetup", base.getPageSetup());
        McpToolSupport.putIfNotNull(item, "resultType", base.getResultType());
        McpToolSupport.putIfNotNull(item, "sqlType", base.getSqlType());
        McpToolSupport.putIfNotNull(item, "version", base.getVersion());
        McpToolSupport.putIfNotNull(item, "releaseTime", base.getReleaseTime());
        McpToolSupport.putIfNotNull(item, "remarks", base.getRemarks());
        return item;
    }

    private List<Map<String, Object>> toParamList(List<BaseApiParam> params) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (params == null) {
            return list;
        }
        for (BaseApiParam param : params) {
            Map<String, Object> item = McpToolSupport.newResult();
            McpToolSupport.putIfNotNull(item, "paramName", param.getParamName());
            McpToolSupport.putIfNotNull(item, "columnName", param.getColumnName());
            McpToolSupport.putIfNotNull(item, "paramType", param.getParamType());
            McpToolSupport.putIfNotNull(item, "required", param.getRequired());
            McpToolSupport.putIfNotNull(item, "operation", param.getOperation());
            McpToolSupport.putIfNotNull(item, "paramDesc", param.getParamDesc());
            list.add(item);
        }
        return list;
    }

    /**
     * 校验可选的枚举入参：未传时返回兜底值（可能为 null 表示"保持原值"）。
     */
    private String requireEnum(String value, String name, Set<String> allowed, String defaultValue) {
        if (isBlank(value)) {
            return defaultValue;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(),
                    name + " 取值不合法：" + value + "，可选 " + String.join(" / ", new TreeSet<>(allowed)));
        }
        return normalized;
    }

    /**
     * 校验分页设置：null 表示保持原值/默认值。
     */
    private Integer requirePageSetup(Integer pageSetup) {
        if (pageSetup == null) {
            return null;
        }
        if (!ALLOWED_PAGE_SETUP.contains(pageSetup)) {
            throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(),
                    "pageSetup 只能为 0（不分页）/ 1（分页）/ 2（分页并统计）");
        }
        return pageSetup;
    }

    /**
     * 校验 SQL 类型：查询语句放行；变更类语句（DML/DDL）必须显式确认。
     *
     * @return true 表示为查询语句
     */
    private boolean requireSqlTypeConfirmed(String sql, String datasourceType, Boolean allowDml) {
        boolean selectOnly = SqlPreviewGuard.isSelectOnly(sql, datasourceType);
        if (selectOnly) {
            return true;
        }
        if (!Boolean.TRUE.equals(allowDml)) {
            throw new CustomException(ErrorStatusEnum.FORBID_OPERATE.getCode(),
                    "检测到该 SQL 不是查询语句（会变更数据，如 INSERT/UPDATE/DELETE/DDL 等）。"
                            + "请先向用户复述该 SQL 的影响范围并取得确认，再以 allowDml=true 重新调用");
        }
        return false;
    }

    private BaseDatasource requireDatasource(String datasourceId) {
        BaseDatasource dataSource = dataSourceService.getDataSource(support.requireInteger(datasourceId, "datasourceId"));
        if (dataSource == null) {
            throw new CustomException(ErrorStatusEnum.DATASOURCE_NOT_FOUNT.getCode(),
                    "无效的数据源：" + datasourceId + "，可调用 apigo_datasource_list 获取有效列表");
        }
        return dataSource;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
