package cn.crabc.core.app.mcp.tools;

import cn.crabc.core.app.entity.BaseApp;
import cn.crabc.core.app.entity.BaseAppApi;
import cn.crabc.core.app.entity.vo.ApiComboBoxVO;
import cn.crabc.core.app.mcp.McpTestFixture;
import cn.crabc.core.app.mcp.support.McpToolSupport;
import cn.crabc.core.app.service.system.IBaseApiInfoService;
import cn.crabc.core.app.service.system.IBaseAppService;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import cn.crabc.core.datasource.util.PageInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 应用与授权工具测试。
 * <p>
 * 重点：授权默认合并语义（防止 AI 误清空其它授权）、密钥仅创建时回显、
 * appCode 生成需满足控制台校验规则（首字符为字母，长度 5-25）。
 *
 * @author yuqf
 */
class AppToolsTest {

    private IBaseAppService appService;
    private IBaseApiInfoService apiInfoService;
    private AppTools tools;

    @BeforeEach
    void setUp() {
        appService = mock(IBaseAppService.class);
        apiInfoService = mock(IBaseApiInfoService.class);
        tools = new AppTools(appService, apiInfoService, McpTestFixture.support());
        McpTestFixture.bindReadWriteContext();
    }

    @AfterEach
    void tearDown() {
        McpTestFixture.clearContext();
    }

    @Test
    @DisplayName("创建应用：由工具层生成 appCode/appKey/appSecret 并一次性回显")
    void appCreate() {
        when(appService.addApp(any(BaseApp.class))).thenAnswer(invocation -> {
            BaseApp app = invocation.getArgument(0);
            app.setAppId(9L);
            return 1;
        });

        Map<String, Object> output = tools.appCreate("订单小程序", "订单业务调用方");

        assertEquals(9L, output.get("appId"));
        String appCode = String.valueOf(output.get("appCode"));
        String appKey = String.valueOf(output.get("appKey"));
        String appSecret = String.valueOf(output.get("appSecret"));
        assertTrue(appCode.matches("[a-zA-Z][a-zA-Z0-9]{4,24}"), "appCode 需满足控制台校验规则：" + appCode);
        assertEquals(32, appKey.length());
        assertEquals(32, appSecret.length());
        assertNotEquals(appKey, appSecret);
        assertTrue(String.valueOf(output.get("tip")).contains("立即"));

        ArgumentCaptor<BaseApp> captor = ArgumentCaptor.forClass(BaseApp.class);
        verify(appService).addApp(captor.capture());
        assertEquals("订单小程序", captor.getValue().getAppName());
        assertEquals(appKey, captor.getValue().getAppKey());
    }

    @Test
    @DisplayName("创建应用：名称为空直接拒绝")
    void appCreateWithoutName() {
        CustomException ex = assertThrows(CustomException.class, () -> tools.appCreate("  ", null));
        assertEquals(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("应用列表：appSecret 一律脱敏")
    void appListMasksSecret() {
        BaseApp app = new BaseApp();
        app.setAppId(9L);
        app.setAppName("订单小程序");
        app.setAppCode("a1b2c3");
        app.setAppKey("k1");
        app.setAppSecret("s1");
        app.setEnabled(1);
        when(appService.appPage(eq("订单"), any(), anyInt(), anyInt()))
                .thenReturn(McpTestFixture.page(List.of(app), 1L, 1, 10));

        Map<String, Object> output = tools.appList("订单", 1, 10);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) output.get("list");
        assertEquals(McpToolSupport.MASK, list.get(0).get("appSecret"));
        assertEquals("a1b2c3", list.get(0).get("appCode"));
    }

    @Test
    @DisplayName("应用列表：回显的 pageSize 必须是钳制后的值，不能回显原始入参")
    void appListEchoesClampedPageSize() {
        when(appService.appPage(eq("订单"), any(), anyInt(), anyInt()))
                .thenReturn(McpTestFixture.page(List.of(), 0L, 1, 20));

        Map<String, Object> output = tools.appList("订单", 1, 1000);

        assertEquals(20, output.get("pageSize"), "传 1000 时应回显钳制后的 20");
    }

    @Test
    @DisplayName("授权：默认合并语义，不清空已有授权")
    void authorizeMerges() {
        AtomicInteger reads = new AtomicInteger();
        when(apiInfoService.getChooseApi(eq(8L), anyInt(), anyInt())).thenAnswer(invocation ->
                reads.incrementAndGet() == 1 ? pageOf(12L, 31L) : pageOf(12L, 31L, 57L));

        Map<String, Object> output = tools.appAuthorize(8L, List.of(57L), null, null);

        assertEquals("merge", output.get("mode"));
        ArgumentCaptor<BaseAppApi> captor = ArgumentCaptor.forClass(BaseAppApi.class);
        verify(apiInfoService).addChooseApi(captor.capture());
        assertEquals(List.of(12L, 31L, 57L), captor.getValue().getApiIds());
        assertEquals(List.of(12L, 31L, 57L), output.get("authorizedApiIds"));
        assertFalse(output.containsKey("warning"));
    }

    @Test
    @DisplayName("授权：存在其它操作人遗留的授权记录时给出告警")
    void authorizeWarnsForeignRecords() {
        AtomicInteger reads = new AtomicInteger();
        when(apiInfoService.getChooseApi(eq(8L), anyInt(), anyInt())).thenAnswer(invocation ->
                reads.incrementAndGet() == 1 ? pageOf(12L, 31L) : pageOf(12L, 31L, 57L, 99L));

        Map<String, Object> output = tools.appAuthorize(8L, List.of(57L), null, null);

        assertTrue(output.containsKey("warning"), "应提示存在其它操作人创建的授权记录");
    }

    @Test
    @DisplayName("授权：replaceAll=true 时全量覆盖")
    void authorizeReplaces() {
        when(apiInfoService.getChooseApi(eq(8L), anyInt(), anyInt())).thenReturn(pageOf(12L, 31L));

        Map<String, Object> output = tools.appAuthorize(8L, List.of(57L), true, true);

        assertEquals("replace", output.get("mode"));
        ArgumentCaptor<BaseAppApi> captor = ArgumentCaptor.forClass(BaseAppApi.class);
        verify(apiInfoService).addChooseApi(captor.capture());
        assertEquals(List.of(57L), captor.getValue().getApiIds());
    }

    @Test
    @DisplayName("授权：replaceAll=true 且列表为空表示清空授权")
    void authorizeClearAll() {
        when(apiInfoService.getChooseApi(eq(8L), anyInt(), anyInt())).thenReturn(pageOf(12L, 31L));

        tools.appAuthorize(8L, List.of(), true, true);

        ArgumentCaptor<BaseAppApi> captor = ArgumentCaptor.forClass(BaseAppApi.class);
        verify(apiInfoService).addChooseApi(captor.capture());
        assertTrue(captor.getValue().getApiIds().isEmpty());
    }

    @Test
    @DisplayName("安全：全量覆盖必须显式 confirm=true，否则拒绝")
    void authorizeReplaceRequiresConfirmation() {
        CustomException ex = assertThrows(CustomException.class,
                () -> tools.appAuthorize(8L, List.of(), true, null));

        assertEquals(ErrorStatusEnum.FORBID_OPERATE.getCode(), ex.getCode());
        verify(apiInfoService, never()).addChooseApi(any(BaseAppApi.class));
    }

    @Test
    @DisplayName("安全：只读令牌不允许授权")
    void authorizeRejectedForReadOnlyToken() {
        McpTestFixture.bindReadOnlyContext();

        CustomException ex = assertThrows(CustomException.class,
                () -> tools.appAuthorize(8L, List.of(57L), null, null));

        assertEquals(ErrorStatusEnum.FORBID_OPERATE.getCode(), ex.getCode());
        verify(apiInfoService, never()).addChooseApi(any(BaseAppApi.class));
    }

    @Test
    @DisplayName("授权：appId 缺失直接拒绝")
    void authorizeWithoutAppId() {
        assertEquals(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(),
                assertThrows(CustomException.class, () -> tools.appAuthorize(null, List.of(1L), null, null)).getCode());
    }

    @Test
    @DisplayName("已授权接口查询：返回接口基础信息")
    void appApis() {
        when(apiInfoService.getChooseApi(eq(8L), anyInt(), anyInt())).thenReturn(pageOf(12L, 31L));

        Map<String, Object> output = tools.appApis(8L, 1, 20);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) output.get("list");
        assertEquals(2, list.size());
        assertEquals(12L, list.get(0).get("apiId"));
    }

    private PageInfo<ApiComboBoxVO> pageOf(Long... apiIds) {
        List<ApiComboBoxVO> list = new java.util.ArrayList<>();
        for (Long apiId : apiIds) {
            ApiComboBoxVO vo = new ApiComboBoxVO();
            vo.setApiId(apiId);
            vo.setApiName("api-" + apiId);
            list.add(vo);
        }
        return McpTestFixture.page(list, list.size(), 1, 1000);
    }
}
