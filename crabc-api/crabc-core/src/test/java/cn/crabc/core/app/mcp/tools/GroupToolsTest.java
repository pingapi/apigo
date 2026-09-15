package cn.crabc.core.app.mcp.tools;

import cn.crabc.core.app.entity.BaseGroup;
import cn.crabc.core.app.entity.vo.ApiComboBoxVO;
import cn.crabc.core.app.entity.vo.BaseGroupVO;
import cn.crabc.core.app.mcp.McpTestFixture;
import cn.crabc.core.app.service.system.IBaseGroupService;
import cn.crabc.core.app.util.UserThreadLocal;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分组工具测试。
 *
 * @author yuqf
 */
class GroupToolsTest {

    private IBaseGroupService groupService;
    private GroupTools tools;

    @BeforeEach
    void setUp() {
        groupService = mock(IBaseGroupService.class);
        tools = new GroupTools(groupService, McpTestFixture.support());
        McpTestFixture.bindReadWriteContext();
    }

    @AfterEach
    void tearDown() {
        McpTestFixture.clearContext();
    }

    @Test
    @DisplayName("分组树：裁剪出 groupId/名称/接口，去掉空集合")
    void groupList() {
        BaseGroupVO root = new BaseGroupVO();
        root.setGroupId(3);
        root.setGroupName("订单");
        root.setParentId(0);

        ApiComboBoxVO api = new ApiComboBoxVO();
        api.setApiId(57L);
        api.setApiName("近7天每日订单量");
        api.setApiStatus("release");
        root.setApis(List.of(api));

        BaseGroupVO child = new BaseGroupVO();
        child.setGroupId(4);
        child.setGroupName("订单-子分组");
        root.setChildren(List.of(child));

        when(groupService.groupTree(eq(McpTestFixture.USER_ID), any())).thenReturn(List.of(root));

        List<Map<String, Object>> output = tools.groupList(null);

        assertEquals(1, output.size());
        assertEquals(3, output.get(0).get("groupId"));
        assertEquals("订单", output.get(0).get("groupName"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> apis = (List<Map<String, Object>>) output.get(0).get("apis");
        assertEquals(57L, apis.get(0).get("apiId"));
        assertEquals("release", apis.get(0).get("apiStatus"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) output.get(0).get("children");
        assertEquals(4, children.get(0).get("groupId"));
    }

    @Test
    @DisplayName("分组树：无分组时返回空列表")
    void groupListEmpty() {
        when(groupService.groupTree(any(), any())).thenReturn(null);
        assertEquals(0, tools.groupList(null).size());
    }

    @Test
    @DisplayName("新建分组：默认一级分组，写入归属用户，并回传生成的 groupId")
    void groupCreate() {
        when(groupService.addGroup(any(BaseGroup.class))).thenAnswer(invocation -> {
            BaseGroup saved = invocation.getArgument(0);
            saved.setGroupId(9);
            return 1;
        });

        Map<String, Object> output = tools.groupCreate("订单", null, "订单相关接口");

        assertEquals(9, output.get("groupId"), "必须回传 groupId，否则 AI 还要再查一次");
        assertEquals("订单", output.get("groupName"));

        ArgumentCaptor<BaseGroup> captor = ArgumentCaptor.forClass(BaseGroup.class);
        verify(groupService).addGroup(captor.capture());
        BaseGroup saved = captor.getValue();
        assertEquals(0, saved.getParentId());
        assertEquals("订单相关接口", saved.getGroupDesc());
        assertEquals(McpTestFixture.USER_ID, saved.getCreateBy(), "分组按 createBy 判权限，必须有归属用户");
        assertNull(UserThreadLocal.get());
    }

    @Test
    @DisplayName("安全：只读令牌不允许新建分组")
    void groupCreateRejectedForReadOnlyToken() {
        McpTestFixture.bindReadOnlyContext();

        CustomException ex = assertThrows(CustomException.class, () -> tools.groupCreate("订单", null, null));

        assertEquals(ErrorStatusEnum.FORBID_OPERATE.getCode(), ex.getCode());
        verify(groupService, never()).addGroup(any(BaseGroup.class));
    }

    @Test
    @DisplayName("新建分组：指定父分组")
    void groupCreateWithParent() {
        when(groupService.addGroup(any(BaseGroup.class))).thenReturn(1);

        tools.groupCreate("订单-子分组", 3, null);

        ArgumentCaptor<BaseGroup> captor = ArgumentCaptor.forClass(BaseGroup.class);
        verify(groupService).addGroup(captor.capture());
        assertEquals(3, captor.getValue().getParentId());
    }

    @Test
    @DisplayName("新建分组：名称为空直接拒绝")
    void groupCreateWithoutName() {
        CustomException ex = assertThrows(CustomException.class, () -> tools.groupCreate(" ", null, null));
        assertEquals(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), ex.getCode());
    }
}
