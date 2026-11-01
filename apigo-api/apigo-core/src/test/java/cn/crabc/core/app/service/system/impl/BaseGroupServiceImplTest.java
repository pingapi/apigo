package cn.crabc.core.app.service.system.impl;

import cn.crabc.core.app.entity.vo.ApiComboBoxVO;
import cn.crabc.core.app.entity.vo.BaseGroupVO;
import cn.crabc.core.app.mapper.BaseApiInfoMapper;
import cn.crabc.core.app.mapper.BaseGroupMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 分组树测试。
 * <p>
 * 历史数据中接口的 group_id 可能为空（直接写库、旧版本导入等），此时分组树整体仍须可用：
 * 无归属的接口不挂到任何分组，其余分组照常返回。
 *
 * @author yuqf
 */
class BaseGroupServiceImplTest {

    private BaseGroupMapper baseGroupMapper;
    private BaseApiInfoMapper apiInfoMapper;
    private BaseGroupServiceImpl service;

    @BeforeEach
    void setUp() {
        baseGroupMapper = mock(BaseGroupMapper.class);
        apiInfoMapper = mock(BaseApiInfoMapper.class);
        service = new BaseGroupServiceImpl();
        ReflectionTestUtils.setField(service, "baseGroupMapper", baseGroupMapper);
        ReflectionTestUtils.setField(service, "apiInfoMapper", apiInfoMapper);
    }

    @Test
    @DisplayName("接口 group_id 为空时分组树仍可用，且只挂载有归属的接口")
    void groupTreeToleratesNullGroupId() {
        when(baseGroupMapper.selectList("1")).thenReturn(List.of(group(1, "默认分组", 0), group(2, "订单分组", 1)));
        when(apiInfoMapper.selectApiGroup(null, "1"))
                .thenReturn(List.of(api(100L, 1), api(101L, 2), api(102L, null)));

        List<BaseGroupVO> tree = service.groupTree("1", null);

        assertEquals(1, tree.size());
        BaseGroupVO root = tree.get(0);
        assertEquals(1, root.getApis().size());
        assertEquals(100L, root.getApis().get(0).getApiId());

        BaseGroupVO child = root.getChildren().get(0);
        assertEquals(2, child.getGroupId());
        assertEquals(1, child.getApis().size());
        assertEquals(101L, child.getApis().get(0).getApiId());
    }

    @Test
    @DisplayName("分组树正常挂载：无空值数据时行为不变")
    void groupTreeWithFullGroupIds() {
        when(baseGroupMapper.selectList("1")).thenReturn(List.of(group(1, "默认分组", 0)));
        when(apiInfoMapper.selectApiGroup(null, "1")).thenReturn(List.of(api(100L, 1), api(103L, 1)));

        List<BaseGroupVO> tree = service.groupTree("1", null);

        assertEquals(2, tree.get(0).getApis().size());
    }

    private BaseGroupVO group(Integer groupId, String groupName, Integer parentId) {
        BaseGroupVO vo = new BaseGroupVO();
        vo.setGroupId(groupId);
        vo.setGroupName(groupName);
        vo.setParentId(parentId);
        return vo;
    }

    private ApiComboBoxVO api(Long apiId, Integer groupId) {
        ApiComboBoxVO vo = new ApiComboBoxVO();
        vo.setApiId(apiId);
        vo.setApiName("接口-" + apiId);
        vo.setApiStatus("release");
        vo.setGroupId(groupId);
        return vo;
    }
}
