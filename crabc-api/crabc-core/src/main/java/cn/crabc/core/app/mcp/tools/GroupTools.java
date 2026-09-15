package cn.crabc.core.app.mcp.tools;

import cn.crabc.core.app.entity.BaseGroup;
import cn.crabc.core.app.entity.vo.ApiComboBoxVO;
import cn.crabc.core.app.entity.vo.BaseGroupVO;
import cn.crabc.core.app.mcp.support.McpToolSupport;
import cn.crabc.core.app.service.system.IBaseGroupService;
import cn.crabc.core.app.util.UserThreadLocal;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP 分组工具。
 *
 * @author yuqf
 */
@Component
public class GroupTools {

    private final IBaseGroupService groupService;
    private final McpToolSupport support;

    public GroupTools(IBaseGroupService groupService, McpToolSupport support) {
        this.groupService = groupService;
        this.support = support;
    }

    @McpTool(name = "apigo_group_list",
            description = """
                    查询平台的接口分组树，返回分组ID、分组名称及其下的接口。
                    用途：创建接口（apigo_api_create）前获取可用的 groupId。
                    无参数调用返回全部分组；失败无副作用。
                    """,
            annotations = @McpTool.McpAnnotations(title = "查询接口分组", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public List<Map<String, Object>> groupList(
            @McpToolParam(description = "可选，只查询指定接口所在的分组", required = false) Long apiId) {
        return support.bindUser("apigo_group_list", () -> {
            List<BaseGroupVO> tree = groupService.groupTree(support.userId(), apiId);
            List<Map<String, Object>> result = new ArrayList<>();
            if (tree != null) {
                for (BaseGroupVO node : tree) {
                    result.add(toGroup(node));
                }
            }
            return result;
        });
    }

    @McpTool(name = "apigo_group_create",
            description = """
                    新建一个接口分组。当 apigo_group_list 找不到合适分组时使用。
                    parentId 缺省为 0（一级分组）。
                    返回新建的 groupId 与 groupName，groupId 可直接用于 apigo_api_create。
                    """,
            annotations = @McpTool.McpAnnotations(title = "新建接口分组", readOnlyHint = false,
                    destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> groupCreate(
            @McpToolParam(description = "分组名称", required = true) String groupName,
            @McpToolParam(description = "父分组ID，一级分组传 0", required = false) Integer parentId,
            @McpToolParam(description = "分组描述", required = false) String groupDesc) {
        return support.bindWrite("apigo_group_create", "groupName=" + groupName, () -> {
            BaseGroup group = new BaseGroup();
            group.setGroupName(support.requireText(groupName, "groupName"));
            group.setParentId(parentId == null ? 0 : parentId);
            group.setGroupDesc(groupDesc);
            group.setCreateBy(UserThreadLocal.getUserId());
            groupService.addGroup(group);

            Map<String, Object> output = McpToolSupport.newResult();
            // 主键由 mapper 的 useGeneratedKeys 回填，必须回传否则 AI 还需再查一次
            McpToolSupport.putIfNotNull(output, "groupId", group.getGroupId());
            output.put("groupName", group.getGroupName());
            return output;
        });
    }

    private Map<String, Object> toGroup(BaseGroupVO node) {
        Map<String, Object> map = McpToolSupport.newResult();
        McpToolSupport.putIfNotNull(map, "groupId", node.getGroupId());
        McpToolSupport.putIfNotNull(map, "groupName", node.getGroupName());
        McpToolSupport.putIfNotNull(map, "parentId", node.getParentId());
        McpToolSupport.putIfNotNull(map, "groupDesc", node.getGroupDesc());

        List<ApiComboBoxVO> apis = node.getApis();
        if (apis != null && !apis.isEmpty()) {
            List<Map<String, Object>> apiList = new ArrayList<>();
            for (ApiComboBoxVO api : apis) {
                Map<String, Object> item = McpToolSupport.newResult();
                McpToolSupport.putIfNotNull(item, "apiId", api.getApiId());
                McpToolSupport.putIfNotNull(item, "apiName", api.getApiName());
                McpToolSupport.putIfNotNull(item, "apiStatus", api.getApiStatus());
                apiList.add(item);
            }
            map.put("apis", apiList);
        }

        List<BaseGroupVO> children = node.getChildren();
        if (children != null && !children.isEmpty()) {
            List<Map<String, Object>> childList = new ArrayList<>();
            for (BaseGroupVO child : children) {
                childList.add(toGroup(child));
            }
            map.put("children", childList);
        }
        return map;
    }
}
