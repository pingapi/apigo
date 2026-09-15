package cn.crabc.core.app.mcp.tools;

import cn.crabc.core.app.entity.BaseApiInfo;
import cn.crabc.core.app.entity.BaseApiSql;
import cn.crabc.core.app.entity.param.ApiInfoParam;
import cn.crabc.core.app.entity.param.ApiRateLimitParam;
import cn.crabc.core.app.entity.vo.ApiInfoVO;
import cn.crabc.core.app.entity.vo.ApiRateLimitVO;
import cn.crabc.core.app.mcp.McpTestFixture;
import cn.crabc.core.app.service.core.IBaseDataService;
import cn.crabc.core.app.service.system.IBaseApiInfoService;
import cn.crabc.core.app.util.UserThreadLocal;
import cn.crabc.core.datasource.constant.BaseConstant;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 接口运营工具测试：发布、上下线、限流、自测。
 *
 * @author yuqf
 */
class ApiOpsToolsTest {

    private IBaseApiInfoService apiInfoService;
    private IBaseDataService baseDataService;
    private ApiOpsTools tools;

    @BeforeEach
    void setUp() {
        apiInfoService = mock(IBaseApiInfoService.class);
        baseDataService = mock(IBaseDataService.class);
        tools = new ApiOpsTools(apiInfoService, baseDataService, McpTestFixture.support(),
                McpTestFixture.jsonMapper());
        McpTestFixture.bindReadWriteContext();
    }

    @AfterEach
    void tearDown() {
        McpTestFixture.clearContext();
    }

    @Test
    @DisplayName("发布接口：只需 apiId，发布即上线")
    void publish() {
        when(apiInfoService.getApiInfo(57L)).thenReturn(apiOf("release", 1));

        Map<String, Object> output = tools.apiPublish(57L, true);

        assertEquals("release", output.get("apiStatus"));
        assertEquals(1, output.get("enabled"));
        assertTrue(String.valueOf(output.get("message")).contains("已发布"));

        ArgumentCaptor<ApiInfoParam> captor = ArgumentCaptor.forClass(ApiInfoParam.class);
        verify(apiInfoService).apiPublish(captor.capture());
        assertEquals(57L, captor.getValue().getBaseInfo().getApiId());
        assertNull(captor.getValue().getSqlInfo(), "非草稿路径不需要 SQL 信息");
    }

    @Test
    @DisplayName("发布接口：未确认时拒绝，不落库")
    void publishRequiresConfirmation() {
        CustomException ex = assertThrows(CustomException.class, () -> tools.apiPublish(57L, null));
        assertEquals(ErrorStatusEnum.FORBID_OPERATE.getCode(), ex.getCode());
        assertTrue(ex.getMsg().contains("confirm=true"));
        verify(apiInfoService, never()).apiPublish(any(ApiInfoParam.class));

        assertThrows(CustomException.class, () -> tools.apiPublish(57L, false));
    }

    @Test
    @DisplayName("安全：只读令牌不允许发布接口")
    void publishRejectedForReadOnlyToken() {
        McpTestFixture.bindReadOnlyContext();

        CustomException ex = assertThrows(CustomException.class, () -> tools.apiPublish(57L, true));

        assertEquals(ErrorStatusEnum.FORBID_OPERATE.getCode(), ex.getCode());
        verify(apiInfoService, never()).apiPublish(any(ApiInfoParam.class));
    }

    @Test
    @DisplayName("上下线：enabled 只能为 1 或 0，且必须确认")
    void setEnabled() {
        when(apiInfoService.getApiInfo(57L)).thenReturn(apiOf("release", 0));

        tools.apiSetEnabled(57L, 1, true);
        verify(apiInfoService).updateApiState(57L, null, 1);
        assertEquals(0, tools.apiSetEnabled(57L, 0, true).get("enabled"));

        assertEquals(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(),
                assertThrows(CustomException.class, () -> tools.apiSetEnabled(57L, 2, true)).getCode());
        assertEquals(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(),
                assertThrows(CustomException.class, () -> tools.apiSetEnabled(57L, null, true)).getCode());

        assertEquals(ErrorStatusEnum.FORBID_OPERATE.getCode(),
                assertThrows(CustomException.class, () -> tools.apiSetEnabled(57L, 1, null)).getCode());
    }

    @Test
    @DisplayName("限流配置：三参数全空表示清空")
    void clearRateLimit() {
        tools.apiSetRateLimit(57L, null, null, null);

        ArgumentCaptor<ApiRateLimitParam> captor = ArgumentCaptor.forClass(ApiRateLimitParam.class);
        verify(apiInfoService).saveRateLimit(captor.capture());
        ApiRateLimitParam param = captor.getValue();
        assertEquals(57L, param.getApiId());
        assertNull(param.getWindowValue());
        assertNull(param.getWindowUnit());
        assertNull(param.getLimitCount());
    }

    @Test
    @DisplayName("限流配置：参数不全或单位非法时拒绝")
    void invalidRateLimit() {
        assertThrows(CustomException.class, () -> tools.apiSetRateLimit(57L, 1, "MINUTE", null));
        assertThrows(CustomException.class, () -> tools.apiSetRateLimit(57L, 1, null, 100));
        CustomException ex = assertThrows(CustomException.class,
                () -> tools.apiSetRateLimit(57L, 1, "DAY", 100));
        assertEquals(ErrorStatusEnum.FORBID_OPERATE.getCode(), ex.getCode());
        verify(apiInfoService, never()).saveRateLimit(any(ApiRateLimitParam.class));
    }

    @Test
    @DisplayName("限流配置：保存后回显当前限流规则")
    void saveRateLimitAndEcho() {
        ApiRateLimitVO vo = new ApiRateLimitVO();
        vo.setApiId(57L);
        vo.setApiName("近7天每日订单量");
        vo.setWindowValue(1);
        vo.setWindowUnit("MINUTE");
        vo.setLimitCount(100);
        when(apiInfoService.getRateLimit(57L)).thenReturn(vo);

        Map<String, Object> output = tools.apiSetRateLimit(57L, 1, "minute", 100);

        ArgumentCaptor<ApiRateLimitParam> captor = ArgumentCaptor.forClass(ApiRateLimitParam.class);
        verify(apiInfoService).saveRateLimit(captor.capture());
        assertEquals("MINUTE", captor.getValue().getWindowUnit(), "单位需转大写后落库");

        assertEquals(false, output.get("cleared"));
        assertEquals(100, output.get("limitCount"));
        assertEquals("MINUTE", output.get("windowUnit"));
    }

    @Test
    @DisplayName("接口自测：按实现顺序传参并带上事务/分页参数")
    void apiTest() {
        when(apiInfoService.getApiInfo(57L)).thenReturn(apiOf("release", 1));
        when(baseDataService.execute(eq("3"), eq("mysql"), eq("orders"), anyString(), anyMap()))
                .thenReturn(List.of(Map.of("id", 1)));

        Map<String, Object> output = tools.apiTest(57L, "{\"id\": 1}");

        assertEquals(1, output.get("rowCount"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(baseDataService).execute(eq("3"), eq("mysql"), eq("orders"), anyString(), paramsCaptor.capture());
        Map<String, Object> params = paramsCaptor.getValue();
        assertEquals(1, params.get("id"));
        assertEquals(0, params.get(BaseConstant.TRANSACTION_ENABLED));
        assertEquals(1, params.get(BaseConstant.PAGE_NUM), "接口开启了分页，应给测试加上分页参数");
        assertNull(UserThreadLocal.get());
    }

    @Test
    @DisplayName("接口自测：接口不存在时报错")
    void apiTestNotFound() {
        when(apiInfoService.getApiInfo(99L)).thenReturn(null);
        assertEquals(ErrorStatusEnum.API_NOT_FOUNT.getCode(),
                assertThrows(CustomException.class, () -> tools.apiTest(99L, null)).getCode());
        verify(baseDataService, never()).execute(anyString(), any(), any(), anyString(), anyMap());
    }

    @Test
    @DisplayName("接口自测：无 SQL 脚本时报错")
    void apiTestWithoutSql() {
        ApiInfoVO vo = apiOf("edit", 0);
        vo.getBaseInfo().setSqlScript(null);
        vo.setSqlInfo(new BaseApiSql());
        when(apiInfoService.getApiInfo(57L)).thenReturn(vo);

        assertEquals(ErrorStatusEnum.API_SQL_ERROR.getCode(),
                assertThrows(CustomException.class, () -> tools.apiTest(57L, null)).getCode());
    }

    @Test
    @DisplayName("安全：接口自测拒绝 DML，不能借自测入口变更数据")
    void apiTestRejectsDml() {
        ApiInfoVO vo = apiOf("release", 1);
        vo.getBaseInfo().setSqlScript("DELETE FROM t_order WHERE id = #{id}");
        when(apiInfoService.getApiInfo(57L)).thenReturn(vo);

        CustomException ex = assertThrows(CustomException.class, () -> tools.apiTest(57L, "{\"id\": 1}"));

        assertEquals(ErrorStatusEnum.API_SQL_ERROR.getCode(), ex.getCode());
        verify(baseDataService, never()).execute(anyString(), any(), any(), anyString(), anyMap());
    }

    @Test
    @DisplayName("安全：接口自测拒绝读取服务端文件的函数")
    void apiTestRejectsFileAccessFunction() {
        ApiInfoVO vo = apiOf("release", 1);
        vo.getBaseInfo().setSqlScript("SELECT read_text('/etc/passwd') AS c");
        when(apiInfoService.getApiInfo(57L)).thenReturn(vo);

        assertThrows(CustomException.class, () -> tools.apiTest(57L, null));
        verify(baseDataService, never()).execute(anyString(), any(), any(), anyString(), anyMap());
    }

    private ApiInfoVO apiOf(String status, int enabled) {
        BaseApiInfo base = new BaseApiInfo();
        base.setApiId(57L);
        base.setApiName("近7天每日订单量");
        base.setApiPath("order/daily");
        base.setApiMethod("GET");
        base.setApiStatus(status);
        base.setEnabled(enabled);
        base.setDatasourceId(3);
        base.setDatasourceType("mysql");
        base.setSchemaName("orders");
        base.setSqlScript("SELECT id FROM t_order");
        base.setPageSetup(1);
        base.setTransactionEnabled(0);

        ApiInfoVO vo = new ApiInfoVO();
        vo.setBaseInfo(base);
        return vo;
    }
}
