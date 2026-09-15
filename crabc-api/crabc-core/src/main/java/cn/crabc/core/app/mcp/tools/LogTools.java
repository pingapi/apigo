package cn.crabc.core.app.mcp.tools;

import cn.crabc.core.app.entity.BaseApiLog;
import cn.crabc.core.app.entity.param.ApiLogParam;
import cn.crabc.core.app.entity.vo.ApiLogDailyTrendVO;
import cn.crabc.core.app.entity.vo.ApiLogGroupCountVO;
import cn.crabc.core.app.entity.vo.ApiLogSummaryVO;
import cn.crabc.core.app.mcp.support.McpToolSupport;
import cn.crabc.core.app.service.system.IBaseApiLogService;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import cn.crabc.core.datasource.util.PageInfo;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MCP 日志与统计工具。
 * <p>
 * 能力边界（来自 {@code ApiLogParam} 与 {@code BaseApiLogMapper.xml}）：
 * 只支持 keyword（接口名/路径）、appName、result、时间范围过滤；
 * 不支持按 apiId、耗时、IP 过滤，时间必须成对传入才生效。
 *
 * @author yuqf
 */
@Component
public class LogTools {

    private final IBaseApiLogService logService;
    private final McpToolSupport support;

    public LogTools(IBaseApiLogService logService, McpToolSupport support) {
        this.logService = logService;
        this.support = support;
    }

    @McpTool(name = "apigo_log_query",
            description = """
                    分页查询接口调用日志（不含请求体/响应体，避免敏感数据与上下文膨胀）。
                    过滤条件：keyword（接口名称或路径模糊匹配）、appName（调用凭证名称）、
                    result（success/fail）、startTime 与 endTime（必须成对，格式 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss）。
                    注意：不支持按 apiId 过滤，请用 keyword 传接口路径片段；不传时间范围时默认查全部。
                    """,
            annotations = @McpTool.McpAnnotations(title = "查询调用日志", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> logQuery(
            @McpToolParam(description = "接口名称或路径关键字", required = false) String keyword,
            @McpToolParam(description = "应用/调用凭证名称", required = false) String appName,
            @McpToolParam(description = "请求结果：success 或 fail", required = false) String result,
            @McpToolParam(description = "开始时间，如 2026-09-01 或 2026-09-01 00:00:00", required = false) String startTime,
            @McpToolParam(description = "结束时间，需与 startTime 同时提供", required = false) String endTime,
            @McpToolParam(description = "页码，从 1 开始", required = false) Integer pageNum,
            @McpToolParam(description = "每页条数，默认 10，上限 20", required = false) Integer pageSize) {
        return support.bindUser("apigo_log_query", () -> {
            int num = support.pageNum(pageNum);
            int size = support.pageSize(pageSize);
            ApiLogParam param = buildParam(keyword, appName, result, startTime, endTime);
            param.setPageNum(num);
            param.setPageSize(size);

            PageInfo<?> page = logService.page(param);
            List<Map<String, Object>> list = new ArrayList<>();
            if (page != null && page.getList() != null) {
                for (Object item : page.getList()) {
                    if (item instanceof BaseApiLog log) {
                        list.add(toLog(log));
                    }
                }
            }
            Map<String, Object> output = McpToolSupport.newResult();
            output.put("total", page == null ? 0L : page.getTotal());
            output.put("pageNum", num);
            output.put("pageSize", size);
            output.put("list", list);
            if (isBlank(startTime) || isBlank(endTime)) {
                output.put("tip", "未传完整时间范围，本次为全量查询；如需缩小范围请同时提供 startTime 与 endTime");
            }
            return output;
        });
    }

    @McpTool(name = "apigo_log_summary",
            description = """
                    查询调用日志汇总指标：总调用量、成功量、失败量、成功率、平均耗时、最大耗时。
                    不传时间范围时平台默认统计近 7 天。
                    """,
            annotations = @McpTool.McpAnnotations(title = "查询调用汇总", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> logSummary(
            @McpToolParam(description = "接口名称或路径关键字", required = false) String keyword,
            @McpToolParam(description = "应用/调用凭证名称", required = false) String appName,
            @McpToolParam(description = "开始时间", required = false) String startTime,
            @McpToolParam(description = "结束时间，需与 startTime 同时提供", required = false) String endTime) {
        return support.bindUser("apigo_log_summary", () -> {
            ApiLogSummaryVO summary = logService.summary(buildParam(keyword, appName, null, startTime, endTime));
            Map<String, Object> output = McpToolSupport.newResult();
            if (summary == null) {
                return output;
            }
            McpToolSupport.putIfNotNull(output, "totalCount", summary.getTotalCount());
            McpToolSupport.putIfNotNull(output, "successCount", summary.getSuccessCount());
            McpToolSupport.putIfNotNull(output, "failCount", summary.getFailCount());
            McpToolSupport.putIfNotNull(output, "successRate", summary.getSuccessRate());
            McpToolSupport.putIfNotNull(output, "avgCostTime", summary.getAvgCostTime());
            McpToolSupport.putIfNotNull(output, "maxCostTime", summary.getMaxCostTime());
            return output;
        });
    }

    @McpTool(name = "apigo_stats_top",
            description = """
                    查询调用统计排行/分布。dimension 取值：
                    apis（调用量Top接口）、costApis（耗时Top接口）、ips（调用量Top来源IP）、
                    status（成功/失败状态分布）、dailyTrend（每日调用趋势）。
                    时间范围必须成对传入；不传时平台默认统计近 7 天（dailyTrend 除外）。
                    """,
            annotations = @McpTool.McpAnnotations(title = "查询调用统计", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public List<Map<String, Object>> statsTop(
            @McpToolParam(description = "统计维度：apis / costApis / ips / status / dailyTrend", required = true) String dimension,
            @McpToolParam(description = "开始时间", required = false) String startTime,
            @McpToolParam(description = "结束时间，需与 startTime 同时提供", required = false) String endTime) {
        return support.bindUser("apigo_stats_top", () -> {
            String dim = support.requireText(dimension, "dimension").toLowerCase(Locale.ROOT);
            ApiLogParam param = buildParam(null, null, null, startTime, endTime);
            List<Map<String, Object>> result = new ArrayList<>();
            switch (dim) {
                case "apis", "costapis", "ips", "status" -> {
                    List<ApiLogGroupCountVO> list = switch (dim) {
                        case "costapis" -> logService.topCostApis(param);
                        case "ips" -> logService.topIps(param);
                        case "status" -> logService.statusPie(param);
                        default -> logService.topApis(param);
                    };
                    if (list != null) {
                        for (ApiLogGroupCountVO item : list) {
                            Map<String, Object> row = McpToolSupport.newResult();
                            McpToolSupport.putIfNotNull(row, "groupKey", item.getGroupKey());
                            McpToolSupport.putIfNotNull(row, "groupName", item.getGroupName());
                            McpToolSupport.putIfNotNull(row, "apiPath", item.getApiPath());
                            McpToolSupport.putIfNotNull(row, "count", item.getCount());
                            McpToolSupport.putIfNotNull(row, "avgCostTime", item.getAvgCostTime());
                            McpToolSupport.putIfNotNull(row, "maxCostTime", item.getMaxCostTime());
                            result.add(row);
                        }
                    }
                }
                case "dailytrend" -> {
                    List<ApiLogDailyTrendVO> list = logService.dailyTrend(param);
                    if (list != null) {
                        for (ApiLogDailyTrendVO item : list) {
                            Map<String, Object> row = McpToolSupport.newResult();
                            McpToolSupport.putIfNotNull(row, "statisticDate", item.getStatisticDate());
                            McpToolSupport.putIfNotNull(row, "totalCount", item.getTotalCount());
                            McpToolSupport.putIfNotNull(row, "successCount", item.getSuccessCount());
                            McpToolSupport.putIfNotNull(row, "failCount", item.getFailCount());
                            McpToolSupport.putIfNotNull(row, "avgCostTime", item.getAvgCostTime());
                            result.add(row);
                        }
                    }
                }
                default -> throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(),
                        "dimension 取值不合法：" + dimension
                                + "，可选 apis / costApis / ips / status / dailyTrend");
            }
            return result;
        });
    }

    private ApiLogParam buildParam(String keyword, String appName, String result, String startTime, String endTime) {
        ApiLogParam param = new ApiLogParam();
        param.setKeyword(isBlank(keyword) ? null : keyword);
        param.setAppName(isBlank(appName) ? null : appName);
        param.setResult(isBlank(result) ? null : result);
        // 平台要求 startTime / endTime 成对出现才生效
        if (!isBlank(startTime) && !isBlank(endTime)) {
            param.setStartTime(startTime);
            param.setEndTime(endTime);
        }
        return param;
    }

    private Map<String, Object> toLog(BaseApiLog log) {
        Map<String, Object> item = McpToolSupport.newResult();
        McpToolSupport.putIfNotNull(item, "apiId", log.getApiId());
        McpToolSupport.putIfNotNull(item, "apiName", log.getApiName());
        McpToolSupport.putIfNotNull(item, "apiPath", log.getApiPath());
        McpToolSupport.putIfNotNull(item, "apiMethod", log.getApiMethod());
        McpToolSupport.putIfNotNull(item, "requestStatus", log.getRequestStatus());
        McpToolSupport.putIfNotNull(item, "responseCode", log.getResponseCode());
        McpToolSupport.putIfNotNull(item, "costTime", log.getCostTime());
        McpToolSupport.putIfNotNull(item, "appName", log.getAppName());
        McpToolSupport.putIfNotNull(item, "requestIp", log.getRequestIp());
        McpToolSupport.putIfNotNull(item, "requestTime", log.getRequestTime());
        return item;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
