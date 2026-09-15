package cn.crabc.core.app.mcp.tools;

import cn.crabc.core.app.entity.BaseApiLog;
import cn.crabc.core.app.entity.param.ApiLogParam;
import cn.crabc.core.app.entity.vo.ApiLogDailyTrendVO;
import cn.crabc.core.app.entity.vo.ApiLogGroupCountVO;
import cn.crabc.core.app.entity.vo.ApiLogSummaryVO;
import cn.crabc.core.app.mcp.McpTestFixture;
import cn.crabc.core.app.service.system.IBaseApiLogService;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 日志与统计工具测试。
 *
 * @author yuqf
 */
class LogToolsTest {

    private IBaseApiLogService logService;
    private LogTools tools;

    @BeforeEach
    void setUp() {
        logService = mock(IBaseApiLogService.class);
        tools = new LogTools(logService, McpTestFixture.support());
    }

    @Test
    @DisplayName("日志查询：裁剪字段、不回传请求体与响应体")
    void logQuery() {
        BaseApiLog log = new BaseApiLog();
        log.setApiId(57L);
        log.setApiName("近7天每日订单量");
        log.setApiPath("/api/web/order/daily");
        log.setApiMethod("GET");
        log.setRequestStatus("success");
        log.setCostTime(15L);
        log.setAppName("订单小程序");
        log.setRequestIp("10.0.0.1");
        log.setRequestTime(new Date());
        log.setQueryParam("sensitive=1");
        log.setRequestBody("{\"secret\":\"x\"}");
        log.setResponseBody("{\"big\":\"payload\"}");
        when(logService.page(any(ApiLogParam.class)))
                .thenReturn(McpTestFixture.page(List.of(log), 12L, 1, 10));

        Map<String, Object> output = tools.logQuery("order/daily", null, null, null, null, 1, 10);

        assertEquals(12L, output.get("total"));
        assertTrue(output.containsKey("tip"), "未传完整时间范围应给出提示");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) output.get("list");
        Map<String, Object> row = list.get(0);
        assertEquals("success", row.get("requestStatus"));
        assertEquals(15L, row.get("costTime"));
        assertFalse(row.containsKey("queryParam"));
        assertFalse(row.containsKey("requestBody"));
        assertFalse(row.containsKey("responseBody"));
    }

    @Test
    @DisplayName("日志查询：时间必须成对传入，单独传 startTime 不生效")
    void logQueryTimeMustBePaired() {
        when(logService.page(any(ApiLogParam.class))).thenReturn(McpTestFixture.page(List.of(), 0L, 1, 10));

        tools.logQuery(null, null, null, "2026-09-01 00:00:00", null, 1, 10);

        ArgumentCaptor<ApiLogParam> captor = ArgumentCaptor.forClass(ApiLogParam.class);
        verify(logService).page(captor.capture());
        assertNull(captor.getValue().getStartTime(), "只传开始时间不应生效");
        assertNull(captor.getValue().getEndTime());
    }

    @Test
    @DisplayName("日志查询：成对时间生效，页大小被钳制")
    void logQueryPairedTime() {
        when(logService.page(any(ApiLogParam.class))).thenReturn(McpTestFixture.page(List.of(), 0L, 1, 20));

        tools.logQuery("api", "app", "fail", "2026-09-01 00:00:00", "2026-09-02 00:00:00", null, 500);

        ArgumentCaptor<ApiLogParam> captor = ArgumentCaptor.forClass(ApiLogParam.class);
        verify(logService).page(captor.capture());
        ApiLogParam param = captor.getValue();
        assertEquals("api", param.getKeyword());
        assertEquals("app", param.getAppName());
        assertEquals("fail", param.getResult());
        assertEquals("2026-09-01 00:00:00", param.getStartTime());
        assertEquals("2026-09-02 00:00:00", param.getEndTime());
        assertEquals(1, param.getPageNum());
        assertEquals(20, param.getPageSize(), "页大小需钳制到上限");
    }

    @Test
    @DisplayName("汇总统计：返回成功率与耗时边界")
    void logSummary() {
        ApiLogSummaryVO summary = new ApiLogSummaryVO();
        summary.setTotalCount(100L);
        summary.setSuccessCount(98L);
        summary.setFailCount(2L);
        summary.setSuccessRate(98.0);
        summary.setAvgCostTime(12.5);
        summary.setMaxCostTime(320L);
        when(logService.summary(any(ApiLogParam.class))).thenReturn(summary);

        Map<String, Object> output = tools.logSummary(null, null, null, null);

        assertEquals(100L, output.get("totalCount"));
        assertEquals(98.0, output.get("successRate"));
        assertEquals(320L, output.get("maxCostTime"));
    }

    @Test
    @DisplayName("排行统计：维度合法时映射结果")
    void statsTop() {
        ApiLogGroupCountVO vo = new ApiLogGroupCountVO();
        vo.setGroupKey("57");
        vo.setGroupName("近7天每日订单量");
        vo.setCount(120L);
        when(logService.topApis(any(ApiLogParam.class))).thenReturn(List.of(vo));

        List<Map<String, Object>> output = tools.statsTop("apis", null, null);

        assertEquals(1, output.size());
        assertEquals(120L, output.get(0).get("count"));
        assertEquals("近7天每日订单量", output.get(0).get("groupName"));
        verify(logService).topApis(any(ApiLogParam.class));
    }

    @Test
    @DisplayName("排行统计：每日趋势维度")
    void statsTopDailyTrend() {
        ApiLogDailyTrendVO vo = new ApiLogDailyTrendVO();
        vo.setStatisticDate("2026-09-15");
        vo.setTotalCount(10L);
        vo.setFailCount(1L);
        when(logService.dailyTrend(any(ApiLogParam.class))).thenReturn(List.of(vo));

        List<Map<String, Object>> output = tools.statsTop("dailyTrend", "2026-09-01", "2026-09-15");

        assertEquals("2026-09-15", output.get(0).get("statisticDate"));
        assertEquals(1L, output.get(0).get("failCount"));
    }

    @Test
    @DisplayName("排行统计：非法维度报错并提示可选值")
    void statsTopInvalidDimension() {
        CustomException ex = assertThrows(CustomException.class, () -> tools.statsTop("unknown", null, null));
        assertEquals(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), ex.getCode());
        assertTrue(ex.getMsg().contains("dailyTrend"));
    }

    @Test
    @DisplayName("汇总统计：服务层返回空时给出空结果而不是异常")
    void logSummaryNull() {
        when(logService.summary(any(ApiLogParam.class))).thenReturn(null);
        assertTrue(tools.logSummary(null, null, null, null).isEmpty());
    }

    @Test
    @DisplayName("排行统计：维度大小写不敏感")
    void statsTopCaseInsensitive() {
        when(logService.topIps(any(ApiLogParam.class))).thenReturn(List.of());
        assertEquals(0, tools.statsTop("IPs", null, null).size());
        verify(logService).topIps(any(ApiLogParam.class));
    }
}
