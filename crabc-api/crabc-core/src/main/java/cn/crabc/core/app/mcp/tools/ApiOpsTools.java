package cn.crabc.core.app.mcp.tools;

import cn.crabc.core.app.entity.BaseApiInfo;
import cn.crabc.core.app.entity.BaseApiSql;
import cn.crabc.core.app.entity.param.ApiInfoParam;
import cn.crabc.core.app.entity.param.ApiRateLimitParam;
import cn.crabc.core.app.entity.vo.ApiInfoVO;
import cn.crabc.core.app.entity.vo.ApiRateLimitVO;
import cn.crabc.core.app.mcp.support.McpToolSupport;
import cn.crabc.core.app.mcp.support.SqlPreviewGuard;
import cn.crabc.core.app.service.core.IBaseDataService;
import cn.crabc.core.app.service.system.IBaseApiInfoService;
import cn.crabc.core.datasource.constant.BaseConstant;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MCP 接口运营工具：发布、上下线、限流、自测。
 *
 * @author yuqf
 */
@Component
public class ApiOpsTools {

    private static final int TEST_MAX_ROWS = 20;
    private static final int TEST_MAX_COLUMNS = 30;
    private static final int TEST_MAX_VALUE_LENGTH = 200;

    private static final Set<String> WINDOW_UNITS = Set.of("SECOND", "MINUTE", "HOUR");

    private final IBaseApiInfoService apiInfoService;
    private final IBaseDataService baseDataService;
    private final McpToolSupport support;
    private final JsonMapper jsonMapper;

    public ApiOpsTools(IBaseApiInfoService apiInfoService,
                       IBaseDataService baseDataService,
                       McpToolSupport support,
                       JsonMapper jsonMapper) {
        this.apiInfoService = apiInfoService;
        this.baseDataService = baseDataService;
        this.support = support;
        this.jsonMapper = jsonMapper;
    }

    @McpTool(name = "apigo_api_publish",
            description = """
                    发布接口，发布即上线（apiStatus=release、enabled=1），无需再调用上下线工具。
                    若该接口「已发布且存在暂存内容」，本操作发布的是暂存内容并清空暂存。
                    重要：发布会让接口立即对外可用。调用前必须先向用户复述将要上线的接口信息并取得确认，
                    然后再以 confirm=true 调用本工具；未传 confirm=true 会被拒绝。
                    """,
            annotations = @McpTool.McpAnnotations(title = "发布接口", readOnlyHint = false,
                    destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> apiPublish(
            @McpToolParam(description = "接口ID", required = true) Long apiId,
            @McpToolParam(description = "确认发布：向用户复述并取得确认后传 true", required = true) Boolean confirm) {
        return support.bindWrite("apigo_api_publish", "apiId=" + apiId, () -> {
            if (apiId == null) {
                throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), "必传参数不能为空：apiId");
            }
            McpToolSupport.requireConfirmed(confirm, "发布接口会让其立即对外可用，请先向用户复述接口信息并取得确认");

            // 非草稿路径服务层只用 apiId，其余字段取自库中记录
            ApiInfoParam param = new ApiInfoParam();
            BaseApiInfo base = new BaseApiInfo();
            base.setApiId(apiId);
            param.setBaseInfo(base);
            apiInfoService.apiPublish(param);

            ApiInfoVO after = apiInfoService.getApiInfo(apiId);
            return toStatusOutput(after, "接口已发布并上线，无需再调用上下线工具");
        });
    }

    @McpTool(name = "apigo_api_set_enabled",
            description = """
                    单独上线/下线接口（不改变接口状态）。enabled=1 上线，enabled=0 下线。
                    下线后接口调用返回「接口已下线」；上线会让接口立即对外可用。
                    重要：本操作会立即影响线上可用性，需先向用户复述并取得确认后以 confirm=true 调用。
                    """,
            annotations = @McpTool.McpAnnotations(title = "接口上下线", readOnlyHint = false,
                    destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> apiSetEnabled(
            @McpToolParam(description = "接口ID", required = true) Long apiId,
            @McpToolParam(description = "1 上线，0 下线", required = true) Integer enabled,
            @McpToolParam(description = "确认执行：向用户复述并取得确认后传 true", required = true) Boolean confirm) {
        return support.bindWrite("apigo_api_set_enabled", "apiId=" + apiId + " enabled=" + enabled, () -> {
            if (apiId == null) {
                throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), "必传参数不能为空：apiId");
            }
            if (enabled == null || (enabled != 0 && enabled != 1)) {
                throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), "enabled 只能为 1（上线）或 0（下线）");
            }
            McpToolSupport.requireConfirmed(confirm, "上下线会立即影响线上可用性，请先向用户复述并取得确认");

            apiInfoService.updateApiState(apiId, null, enabled);
            ApiInfoVO after = apiInfoService.getApiInfo(apiId);
            return toStatusOutput(after, enabled == 1 ? "接口已上线" : "接口已下线");
        });
    }

    @McpTool(name = "apigo_api_set_rate_limit",
            description = """
                    配置或清空接口限流。windowUnit 取值 SECOND / MINUTE / HOUR。
                    三个参数全部留空表示清空该接口限流配置（清空后接口将不再限流，请谨慎操作）。
                    失败语义：windowUnit 非法或 windowValue/limitCount 缺一会报错。
                    """,
            annotations = @McpTool.McpAnnotations(title = "配置接口限流", readOnlyHint = false,
                    destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> apiSetRateLimit(
            @McpToolParam(description = "接口ID", required = true) Long apiId,
            @McpToolParam(description = "时间窗口数值，与 windowUnit 配合换算为秒", required = false) Integer windowValue,
            @McpToolParam(description = "时间窗口单位：SECOND / MINUTE / HOUR", required = false) String windowUnit,
            @McpToolParam(description = "窗口内允许的请求次数", required = false) Integer limitCount) {
        return support.bindWrite("apigo_api_set_rate_limit", "apiId=" + apiId, () -> {
            if (apiId == null) {
                throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), "必传参数不能为空：apiId");
            }
            boolean clear = windowValue == null && (windowUnit == null || windowUnit.isBlank()) && limitCount == null;
            ApiRateLimitParam param = new ApiRateLimitParam();
            param.setApiId(apiId);
            if (!clear) {
                if (windowValue == null || windowValue <= 0 || windowUnit == null || windowUnit.isBlank() || limitCount == null) {
                    throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(),
                            "配置限流需要同时提供 windowValue、windowUnit、limitCount；三者全空表示清空限流");
                }
                String unit = windowUnit.trim().toUpperCase(Locale.ROOT);
                if (!WINDOW_UNITS.contains(unit)) {
                    throw new CustomException(ErrorStatusEnum.FORBID_OPERATE.getCode(),
                            "windowUnit 取值不合法：" + windowUnit + "，可选 SECOND / MINUTE / HOUR");
                }
                param.setWindowValue(windowValue);
                param.setWindowUnit(unit);
                param.setLimitCount(limitCount);
            }
            apiInfoService.saveRateLimit(param);

            ApiRateLimitVO vo = apiInfoService.getRateLimit(apiId);
            Map<String, Object> output = McpToolSupport.newResult();
            McpToolSupport.putIfNotNull(output, "apiId", apiId);
            if (vo != null) {
                McpToolSupport.putIfNotNull(output, "apiName", vo.getApiName());
                McpToolSupport.putIfNotNull(output, "apiPath", vo.getApiPath());
                McpToolSupport.putIfNotNull(output, "windowValue", vo.getWindowValue());
                McpToolSupport.putIfNotNull(output, "windowUnit", vo.getWindowUnit());
                McpToolSupport.putIfNotNull(output, "limitCount", vo.getLimitCount());
            }
            output.put("cleared", clear);
            return output;
        });
    }

    @McpTool(name = "apigo_api_test",
            description = """
                    按接口定义直接执行其 SQL 做自测（不经过 HTTP 网关，因此不受认证/限流影响）。
                    参数值通过 paramsJson 传入，如 {"id": 1, "name": "x"}。
                    安全约束：仅允许执行单条 SELECT 查询，并禁止调用读取服务端文件的函数；
                    因此本工具无法用于验证 DML 类接口（INSERT/UPDATE/DELETE 会被直接拒绝）。
                    失败语义：SQL 或参数错误时返回数据库原始错误信息。
                    """,
            annotations = @McpTool.McpAnnotations(title = "接口自测", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> apiTest(
            @McpToolParam(description = "接口ID", required = true) Long apiId,
            @McpToolParam(description = "SQL 占位符取值的 JSON 对象字符串", required = false) String paramsJson) {
        return support.bindUser("apigo_api_test", () -> {
            ApiInfoVO detail = apiInfoService.getApiInfo(apiId);
            if (detail == null || detail.getBaseInfo() == null || detail.getBaseInfo().getApiId() == null) {
                throw new CustomException(ErrorStatusEnum.API_NOT_FOUNT.getCode(), "无效的API：" + apiId);
            }
            BaseApiInfo base = detail.getBaseInfo();
            BaseApiSql sql = detail.getSqlInfo() == null ? new BaseApiSql() : detail.getSqlInfo();
            String sqlScript = base.getSqlScript() != null ? base.getSqlScript() : sql.getSqlScript();
            if (sqlScript == null || sqlScript.isBlank()) {
                throw new CustomException(ErrorStatusEnum.API_SQL_ERROR.getCode(), "该接口没有可执行的 SQL 脚本");
            }
            if (base.getDatasourceId() == null) {
                throw new CustomException(ErrorStatusEnum.DATASOURCE_NOT_FOUNT.getCode(), "该接口未关联数据源");
            }
            // 自测是「直接在库里执行 SQL」，必须与试跑同一套闸门，否则可被用于执行 DML
            SqlPreviewGuard.validatePreviewSql(sqlScript, base.getDatasourceType());

            Map<String, Object> params = new HashMap<>(
                    McpToolSupport.parseParamsJson(jsonMapper, paramsJson, "paramsJson"));
            params.put(BaseConstant.TRANSACTION_ENABLED,
                    base.getTransactionEnabled() == null ? 0 : base.getTransactionEnabled());
            if (base.getPageSetup() != null && base.getPageSetup() != 0) {
                params.put(BaseConstant.PAGE_NUM, 1);
                params.put(BaseConstant.PAGE_SIZE, TEST_MAX_ROWS);
            }

            // 参数顺序为实现顺序：(datasourceId, datasourceType, schema, sql, params)
            Object result = baseDataService.execute(String.valueOf(base.getDatasourceId()),
                    base.getDatasourceType(), base.getSchemaName(), sqlScript, params);
            return McpToolSupport.trimQueryResult(result, TEST_MAX_ROWS, TEST_MAX_COLUMNS, TEST_MAX_VALUE_LENGTH);
        });
    }

    private Map<String, Object> toStatusOutput(ApiInfoVO apiInfoVO, String message) {
        Map<String, Object> output = McpToolSupport.newResult();
        if (apiInfoVO == null || apiInfoVO.getBaseInfo() == null) {
            return output;
        }
        BaseApiInfo base = apiInfoVO.getBaseInfo();
        McpToolSupport.putIfNotNull(output, "apiId", base.getApiId());
        McpToolSupport.putIfNotNull(output, "apiName", base.getApiName());
        McpToolSupport.putIfNotNull(output, "apiMethod", base.getApiMethod());
        McpToolSupport.putIfNotNull(output, "apiPath", base.getApiPath());
        McpToolSupport.putIfNotNull(output, "apiStatus", base.getApiStatus());
        McpToolSupport.putIfNotNull(output, "enabled", base.getEnabled());
        output.put("message", message);
        return output;
    }
}
