package cn.crabc.core.app.mcp.tools;

import cn.crabc.core.app.entity.BaseApiInfo;
import cn.crabc.core.app.entity.BaseApiParam;
import cn.crabc.core.app.entity.BaseApiSql;
import cn.crabc.core.app.entity.BaseDatasource;
import cn.crabc.core.app.entity.param.ApiInfoParam;
import cn.crabc.core.app.entity.vo.ApiInfoVO;
import cn.crabc.core.app.mcp.McpTestFixture;
import cn.crabc.core.app.service.core.IBaseDataService;
import cn.crabc.core.app.service.system.IBaseApiInfoService;
import cn.crabc.core.app.service.system.IBaseDataSourceService;
import cn.crabc.core.app.util.UserThreadLocal;
import cn.crabc.core.datasource.constant.BaseConstant;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
 * 接口开发工具测试：SQL 解析、试跑闸门、创建装配、编辑合并语义。
 *
 * @author yuqf
 */
class ApiDevToolsTest {

    private IBaseApiInfoService apiInfoService;
    private IBaseDataSourceService dataSourceService;
    private IBaseDataService baseDataService;
    private ApiDevTools tools;

    @BeforeEach
    void setUp() {
        apiInfoService = mock(IBaseApiInfoService.class);
        dataSourceService = mock(IBaseDataSourceService.class);
        baseDataService = mock(IBaseDataService.class);
        tools = new ApiDevTools(apiInfoService, dataSourceService, baseDataService,
                McpTestFixture.support(), McpTestFixture.jsonMapper());
        when(dataSourceService.getDataSource(3)).thenReturn(mysqlDatasource());
        McpTestFixture.bindReadWriteContext();
    }

    @AfterEach
    void tearDown() {
        McpTestFixture.clearContext();
    }

    @Test
    @DisplayName("SQL 解析：剥离 script 标签后能识别 #{参数} 与返回列别名")
    void sqlParse() {
        Map<String, Object> output = tools.sqlParse(
                "<script>SELECT id, user_name AS userName FROM t_user WHERE name = #{name}</script>", "mysql");

        assertEquals(List.of("name"), output.get("requestParamNames"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) output.get("resultColumns");
        Map<String, Object> userNameColumn = columns.stream()
                .filter(c -> "username".equals(c.get("alias")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应解析出 username 别名，实际：" + columns));
        assertEquals("user_name", userNameColumn.get("columnName"));
        assertNull(output.get("tip"));
    }

    @Test
    @DisplayName("创建接口：apiPath 归一化 + ApiInfoParam 装配（apiId 必须为 null）")
    void createAssemblesParam() {
        when(apiInfoService.checkApiPath(null, "order/daily/stats", "GET")).thenReturn(false);
        when(apiInfoService.addApiInfo(any(ApiInfoParam.class))).thenReturn(57L);

        Map<String, Object> output = tools.apiCreate("近7天每日订单量", "/order/daily/stats", "3",
                "SELECT id, user_name AS userName FROM t_order WHERE status = 1",
                null, 3, null, null, null, "orders", "t_order", "描述", null);

        assertEquals(57L, output.get("apiId"));
        assertEquals("/api/web/order/daily/stats", output.get("apiPath"));
        assertEquals("edit", output.get("apiStatus"));
        assertEquals(0, output.get("enabled"));
        assertEquals(false, output.get("mutatingSql"), "查询接口不应被标记为变更类");
        assertEquals(List.of("id", "username"), output.get("responseParamNames"));

        ArgumentCaptor<ApiInfoParam> captor = ArgumentCaptor.forClass(ApiInfoParam.class);
        verify(apiInfoService).addApiInfo(captor.capture());
        ApiInfoParam param = captor.getValue();

        assertNull(param.getBaseInfo().getApiId(), "apiId 为 null 才会走新增分支");
        assertEquals("order/daily/stats", param.getBaseInfo().getApiPath(), "必须去掉前导 /");
        assertEquals("GET", param.getBaseInfo().getApiMethod());
        assertEquals("NONE", param.getBaseInfo().getAuthType());
        assertEquals(3, param.getBaseInfo().getGroupId());
        assertEquals("array", param.getBaseInfo().getResultType());
        assertEquals(0, param.getBaseInfo().getPageSetup());

        assertEquals(3, param.getSqlInfo().getDatasourceId());
        assertEquals("mysql", param.getSqlInfo().getDatasourceType());
        assertEquals("orders", param.getSqlInfo().getSchemaName());
        assertEquals("t_order", param.getSqlInfo().getTableName());
    }

    @Test
    @DisplayName("创建接口：自动生成请求参数与返回参数（类型推断与前端一致）")
    void createGeneratesParams() {
        when(apiInfoService.checkApiPath(null, "user/query", "POST")).thenReturn(false);
        when(apiInfoService.addApiInfo(any(ApiInfoParam.class))).thenReturn(58L);

        tools.apiCreate("用户查询", "user/query", "3",
                "SELECT id, user_name AS userName FROM t_user WHERE id = #{id}",
                "post", null, "app_code", 1, "one", "orders", "t_user", null, null);

        ArgumentCaptor<ApiInfoParam> captor = ArgumentCaptor.forClass(ApiInfoParam.class);
        verify(apiInfoService).addApiInfo(captor.capture());
        ApiInfoParam param = captor.getValue();

        assertEquals("POST", param.getBaseInfo().getApiMethod(), "方法需转大写");
        assertEquals("APP_CODE", param.getBaseInfo().getAuthType());
        assertEquals("one", param.getBaseInfo().getResultType());

        List<BaseApiParam> requestParams = param.getRequestParam();
        assertEquals(1, requestParams.size());
        assertEquals("id", requestParams.get(0).getParamName());
        assertEquals("Int", requestParams.get(0).getParamType());
        assertEquals("request", requestParams.get(0).getParamModel());
        assertEquals("Y", requestParams.get(0).getRequired());
        assertEquals("=", requestParams.get(0).getOperation());
        assertEquals("", requestParams.get(0).getColumnName());

        List<BaseApiParam> responseParams = param.getResponseParam();
        Map<String, BaseApiParam> byName = new java.util.HashMap<>();
        responseParams.forEach(p -> byName.put(p.getParamName(), p));
        assertEquals(2, responseParams.size());
        assertEquals("Int", byName.get("id").getParamType());
        assertEquals("user_name", byName.get("username").getColumnName(), "别名映射回原始字段名");
        assertEquals("response", byName.get("username").getParamModel());
    }

    @Test
    @DisplayName("创建接口：剥离 script 标签后再落库")
    void createStripsScriptTags() {
        when(apiInfoService.checkApiPath(null, "t/list", "GET")).thenReturn(false);
        when(apiInfoService.addApiInfo(any(ApiInfoParam.class))).thenReturn(60L);

        tools.apiCreate("列表", "t/list", "3", "<script>SELECT id FROM t_order</script>",
                null, null, null, null, null, null, null, null, null);

        ArgumentCaptor<ApiInfoParam> captor = ArgumentCaptor.forClass(ApiInfoParam.class);
        verify(apiInfoService).addApiInfo(captor.capture());
        assertEquals("SELECT id FROM t_order", captor.getValue().getSqlInfo().getSqlScript());
    }

    @Test
    @DisplayName("创建接口：路径冲突直接拒绝且不落库")
    void createRejectsDuplicatePath() {
        when(apiInfoService.checkApiPath(null, "dup/path", "GET")).thenReturn(true);

        CustomException ex = assertThrows(CustomException.class, () -> tools.apiCreate("重复", "dup/path", "3",
                "SELECT id FROM t_order", null, null, null, null, null, null, null, null, null));

        assertEquals(50011, ex.getCode());
        assertTrue(ex.getMsg().contains("已存在"));
        verify(apiInfoService, never()).addApiInfo(any(ApiInfoParam.class));
    }

    @Test
    @DisplayName("接口详情：不存在时抛业务异常")
    void detailNotFound() {
        when(apiInfoService.getApiInfo(99L)).thenReturn(null);
        CustomException ex = assertThrows(CustomException.class, () -> tools.apiDetail(99L));
        assertEquals(ErrorStatusEnum.API_NOT_FOUNT.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("接口详情：SQL 超长截断并回传 hasDraft")
    void detailTrimsSql() {
        ApiInfoVO current = currentApi();
        current.getBaseInfo().setSqlScript("SELECT " + "x".repeat(3000));
        current.setHasDraft(true);
        when(apiInfoService.getApiInfo(57L)).thenReturn(current);

        Map<String, Object> output = tools.apiDetail(57L);

        assertEquals(true, output.get("hasDraft"));
        @SuppressWarnings("unchecked")
        Map<String, Object> sqlInfo = (Map<String, Object>) output.get("sqlInfo");
        assertTrue(((String) sqlInfo.get("sqlScript")).contains("已截断"));
        assertTrue(((String) sqlInfo.get("sqlScript")).length() < 2100);
    }

    @Test
    @DisplayName("SQL 试跑：非 SELECT 直接拒绝，不触碰执行层")
    void previewRejectsMutation() {
        CustomException ex = assertThrows(CustomException.class,
                () -> tools.sqlPreview("3", "UPDATE t_order SET status = 1", null, "orders"));

        assertEquals(ErrorStatusEnum.API_SQL_ERROR.getCode(), ex.getCode());
        verify(baseDataService, never()).execute(anyString(), anyString(), any(), anyString(), anyMap());
    }

    @Test
    @DisplayName("SQL 试跑：追加 LIMIT、按实现顺序传参并带上 preview 执行参数")
    void previewAppendsLimitAndParams() {
        // 用 LinkedHashMap 保证列顺序可断言（Map.of 的迭代顺序不确定）
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("d", "2026-09-09");
        row.put("c", 7);
        when(baseDataService.execute(eq("3"), eq("mysql"), eq("orders"), anyString(), anyMap()))
                .thenReturn(List.of(row));

        Map<String, Object> output = tools.sqlPreview("3", "SELECT DATE(create_time) d, COUNT(*) c FROM t_order",
                "{\"status\": 1}", "orders");

        assertEquals(1, output.get("rowCount"));
        assertEquals(List.of("d", "c"), output.get("columns"));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(baseDataService).execute(eq("3"), eq("mysql"), eq("orders"), sqlCaptor.capture(), paramsCaptor.capture());

        assertTrue(sqlCaptor.getValue().contains("LIMIT 100"), sqlCaptor.getValue());
        Map<String, Object> params = paramsCaptor.getValue();
        assertEquals(1, params.get("status"), "用户参数应透传给执行层");
        assertEquals("preview", params.get(BaseConstant.BASE_API_EXEC_TYPE));
        assertEquals(BaseConstant.PAGE_ONLY, params.get(BaseConstant.PAGE_SETUP));
        assertEquals("mysql", params.get(BaseConstant.DATA_SOURCE_TYPE));
    }

    @Test
    @DisplayName("SQL 试跑：paramsJson 非法时报错")
    void previewInvalidParamsJson() {
        CustomException ex = assertThrows(CustomException.class,
                () -> tools.sqlPreview("3", "SELECT id FROM t_order", "not-a-json", null));
        assertEquals(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("SQL 试跑：数据源不存在时报错")
    void previewDatasourceNotFound() {
        when(dataSourceService.getDataSource(99)).thenReturn(null);
        CustomException ex = assertThrows(CustomException.class,
                () -> tools.sqlPreview("99", "SELECT id FROM t_order", null, null));
        assertEquals(ErrorStatusEnum.DATASOURCE_NOT_FOUNT.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("编辑接口：SQL 未变化时沿用已有参数定义，不覆盖用户定制")
    void updateKeepsExistingParams() {
        ApiInfoVO current = currentApi();
        when(apiInfoService.getApiInfo(57L)).thenReturn(current);

        Map<String, Object> output = tools.apiUpdate(57L, null, null, null, null, null, null, null, null, null,
                null, null, "新描述", null);

        assertEquals(false, output.get("sqlChanged"));
        assertTrue(((List<?>) output.get("regenerated")).isEmpty());

        ArgumentCaptor<ApiInfoParam> captor = ArgumentCaptor.forClass(ApiInfoParam.class);
        verify(apiInfoService).updateApiInfo(captor.capture());
        ApiInfoParam param = captor.getValue();
        assertSame(current.getRequestParam(), param.getRequestParam(), "应沿用现有参数定义");
        assertEquals("新描述", param.getBaseInfo().getRemarks());
        assertEquals("order/daily", param.getBaseInfo().getApiPath());
        assertEquals(3, param.getBaseInfo().getDatasourceId(), "未传数据源应沿用原值");
        assertEquals("release", param.getBaseInfo().getApiStatus());
        assertNull(UserThreadLocal.get());
    }

    @Test
    @DisplayName("编辑接口：SQL 变化时重新解析参数")
    void updateRegeneratesParamsOnSqlChange() {
        ApiInfoVO current = currentApi();
        when(apiInfoService.getApiInfo(57L)).thenReturn(current);

        Map<String, Object> output = tools.apiUpdate(57L, null, null, null,
                "SELECT id, status FROM t_order WHERE id = #{orderId}", null, null, null, null, null,
                null, null, null, null);

        assertEquals(true, output.get("sqlChanged"));

        ArgumentCaptor<ApiInfoParam> captor = ArgumentCaptor.forClass(ApiInfoParam.class);
        verify(apiInfoService).updateApiInfo(captor.capture());
        ApiInfoParam param = captor.getValue();
        assertNotSame(current.getRequestParam(), param.getRequestParam());
        assertEquals(1, param.getRequestParam().size());
        assertEquals("orderId", param.getRequestParam().get(0).getParamName());
        assertEquals("SELECT id, status FROM t_order WHERE id = #{orderId}", param.getSqlInfo().getSqlScript());
    }

    @Test
    @DisplayName("编辑接口：改路径时做唯一性校验")
    void updateRejectsDuplicatePath() {
        ApiInfoVO current = currentApi();
        when(apiInfoService.getApiInfo(57L)).thenReturn(current);
        when(apiInfoService.checkApiPath(57L, "order/other", "GET")).thenReturn(true);

        CustomException ex = assertThrows(CustomException.class,
                () -> tools.apiUpdate(57L, null, "order/other", null, null, null, null, null, null, null, null, null,
                        null, null));

        assertEquals(50011, ex.getCode());
        verify(apiInfoService, never()).updateApiInfo(any(ApiInfoParam.class));
    }

    @Test
    @DisplayName("编辑接口：接口不存在时报错")
    void updateNotFound() {
        when(apiInfoService.getApiInfo(99L)).thenReturn(null);
        assertThrows(CustomException.class, () -> tools.apiUpdate(99L, "n", null, null, null, null, null, null,
                null, null, null, null, null, null));
        verify(apiInfoService, never()).updateApiInfo(any(ApiInfoParam.class));
    }

    @Test
    @DisplayName("安全：创建变更类接口必须显式 allowDml=true，否则拒绝")
    void createRejectsDmlWithoutConfirmation() {
        CustomException ex = assertThrows(CustomException.class, () -> tools.apiCreate("删除订单", "order/delete", "3",
                "DELETE FROM t_order WHERE id = #{id}", null, null, null, null, null, null, null, null, null));

        assertEquals(ErrorStatusEnum.FORBID_OPERATE.getCode(), ex.getCode());
        assertTrue(ex.getMsg().contains("allowDml"));
        verify(apiInfoService, never()).addApiInfo(any(ApiInfoParam.class));
    }

    @Test
    @DisplayName("安全：显式 allowDml=true 时允许创建变更类接口，并在返回体中告警")
    void createAllowsDmlWithConfirmation() {
        when(apiInfoService.addApiInfo(any(ApiInfoParam.class))).thenReturn(61L);

        Map<String, Object> output = tools.apiCreate("删除订单", "order/delete", "3",
                "DELETE FROM t_order WHERE id = #{id}", null, null, null, null, null, null, null, null, true);

        assertEquals(61L, output.get("apiId"));
        assertEquals(true, output.get("mutatingSql"));
        assertTrue(String.valueOf(output.get("tip")).contains("变更数据"));
    }

    @Test
    @DisplayName("安全：非法枚举值直接拒绝，避免写入脏数据")
    void createRejectsInvalidEnum() {
        assertEquals(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(),
                assertThrows(CustomException.class, () -> tools.apiCreate("接口", "t/a", "3", "SELECT id FROM t_order",
                        "TRACE", null, null, null, null, null, null, null, null)).getCode());

        assertEquals(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(),
                assertThrows(CustomException.class, () -> tools.apiCreate("接口", "t/a", "3", "SELECT id FROM t_order",
                        null, null, "OAUTH2", null, null, null, null, null, null)).getCode());

        assertEquals(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(),
                assertThrows(CustomException.class, () -> tools.apiCreate("接口", "t/a", "3", "SELECT id FROM t_order",
                        null, null, null, 9, null, null, null, null, null)).getCode());
        verify(apiInfoService, never()).addApiInfo(any(ApiInfoParam.class));
    }

    @Test
    @DisplayName("安全：只读令牌不允许创建接口")
    void createRejectedForReadOnlyToken() {
        McpTestFixture.bindReadOnlyContext();

        CustomException ex = assertThrows(CustomException.class, () -> tools.apiCreate("接口", "t/a", "3",
                "SELECT id FROM t_order", null, null, null, null, null, null, null, null, null));

        assertEquals(ErrorStatusEnum.FORBID_OPERATE.getCode(), ex.getCode());
        verify(apiInfoService, never()).addApiInfo(any(ApiInfoParam.class));
    }

    @Test
    @DisplayName("安全：试跑拒绝读取服务端文件的函数（duckdb read_text 等）")
    void previewRejectsFileAccessFunction() {
        CustomException ex = assertThrows(CustomException.class,
                () -> tools.sqlPreview("3", "SELECT read_text('/etc/passwd') AS c", null, null));

        assertEquals(ErrorStatusEnum.API_SQL_ERROR.getCode(), ex.getCode());
        verify(baseDataService, never()).execute(anyString(), anyString(), any(), anyString(), anyMap());
    }

    @Test
    @DisplayName("安全：编辑为变更类 SQL 时同样需要 allowDml=true")
    void updateRejectsDmlWithoutConfirmation() {
        when(apiInfoService.getApiInfo(57L)).thenReturn(currentApi());

        CustomException ex = assertThrows(CustomException.class, () -> tools.apiUpdate(57L, null, null, null,
                "DELETE FROM t_order", null, null, null, null, null, null, null, null, null));

        assertEquals(ErrorStatusEnum.FORBID_OPERATE.getCode(), ex.getCode());
        verify(apiInfoService, never()).updateApiInfo(any(ApiInfoParam.class));
    }

    private BaseDatasource mysqlDatasource() {
        BaseDatasource datasource = new BaseDatasource();
        datasource.setDatasourceId(3);
        datasource.setDatasourceName("orders");
        datasource.setDatasourceType("mysql");
        return datasource;
    }

    private ApiInfoVO currentApi() {
        BaseApiInfo base = new BaseApiInfo();
        base.setApiId(57L);
        base.setApiName("近7天每日订单量");
        base.setApiPath("order/daily");
        base.setApiMethod("GET");
        base.setApiStatus("release");
        base.setEnabled(1);
        base.setAuthType("APP_CODE");
        base.setGroupId(3);
        base.setPageSetup(0);
        base.setDatasourceId(3);
        base.setDatasourceType("mysql");
        base.setSchemaName("orders");
        base.setSqlScript("SELECT id FROM t_order");

        BaseApiSql sql = new BaseApiSql();
        sql.setApiId(57L);
        sql.setDatasourceId(3);
        sql.setDatasourceType("mysql");
        sql.setSchemaName("orders");
        sql.setSqlScript("SELECT id FROM t_order");

        BaseApiParam requestParam = new BaseApiParam();
        requestParam.setParamName("id");
        requestParam.setParamModel("request");

        ApiInfoVO vo = new ApiInfoVO();
        vo.setBaseInfo(base);
        vo.setSqlInfo(sql);
        vo.setRequestParam(new ArrayList<>(List.of(requestParam)));
        vo.setResponseParam(new ArrayList<>());
        vo.setHasDraft(false);
        return vo;
    }
}
