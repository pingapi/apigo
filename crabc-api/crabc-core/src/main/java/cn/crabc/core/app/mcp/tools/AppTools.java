package cn.crabc.core.app.mcp.tools;

import cn.crabc.core.app.entity.BaseApp;
import cn.crabc.core.app.entity.BaseAppApi;
import cn.crabc.core.app.entity.vo.ApiComboBoxVO;
import cn.crabc.core.app.mcp.support.McpToolSupport;
import cn.crabc.core.app.service.system.IBaseApiInfoService;
import cn.crabc.core.app.service.system.IBaseAppService;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import cn.crabc.core.datasource.util.PageInfo;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * MCP 应用与授权工具。
 * <p>
 * 说明：服务端 {@code addApp} 不生成 appCode/appKey/appSecret（控制台由前端 uuid 生成），
 * 因此由本层生成后一次性回显给用户。
 *
 * @author yuqf
 */
@Component
public class AppTools {

    private static final String APP_CODE_PREFIX = "a";

    /**
     * 读取已有授权时的分页大小与最大页数（超出会在返回体中告警，避免静默丢失授权）。
     */
    private static final int AUTH_PAGE_SIZE = 200;
    private static final int MAX_AUTH_PAGES = 100;

    private final IBaseAppService appService;
    private final IBaseApiInfoService apiInfoService;
    private final McpToolSupport support;

    public AppTools(IBaseAppService appService, IBaseApiInfoService apiInfoService, McpToolSupport support) {
        this.appService = appService;
        this.apiInfoService = apiInfoService;
        this.support = support;
    }

    @McpTool(name = "apigo_app_list",
            description = """
                    分页查询调用应用（凭证），返回 appId、应用名、appCode、appKey 与启用状态。
                    appSecret 不会返回，只在 apigo_app_create 创建时回显一次。
                    用途：获取 appId，供 apigo_app_authorize 授权接口使用。
                    """,
            annotations = @McpTool.McpAnnotations(title = "查询应用列表", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> appList(
            @McpToolParam(description = "应用名称关键字", required = false) String appName,
            @McpToolParam(description = "页码，从 1 开始", required = false) Integer pageNum,
            @McpToolParam(description = "每页条数，默认 10，上限 20", required = false) Integer pageSize) {
        return support.bindUser("apigo_app_list", () -> {
            int num = support.pageNum(pageNum);
            int size = support.pageSize(pageSize);
            PageInfo<BaseApp> page = appService.appPage(appName, null, num, size);

            List<Map<String, Object>> list = new ArrayList<>();
            if (page != null && page.getList() != null) {
                for (BaseApp app : page.getList()) {
                    Map<String, Object> item = McpToolSupport.newResult();
                    McpToolSupport.putIfNotNull(item, "appId", app.getAppId());
                    McpToolSupport.putIfNotNull(item, "appName", app.getAppName());
                    McpToolSupport.putIfNotNull(item, "appDesc", app.getAppDesc());
                    McpToolSupport.putIfNotNull(item, "appCode", app.getAppCode());
                    McpToolSupport.putIfNotNull(item, "appKey", app.getAppKey());
                    McpToolSupport.putIfNotNull(item, "enabled", app.getEnabled());
                    item.put("appSecret", McpToolSupport.MASK);
                    list.add(item);
                }
            }
            Map<String, Object> output = McpToolSupport.newResult();
            output.put("total", page == null ? 0L : page.getTotal());
            output.put("pageNum", num);
            output.put("pageSize", size);
            output.put("list", list);
            return output;
        });
    }

    @McpTool(name = "apigo_app_create",
            description = """
                    创建调用应用（凭证），平台自动生成 appCode / appKey / appSecret 并在本次结果中回显。
                    重要：appSecret 只在本次返回中出现一次，请提示用户立即保存，后续接口无法再查询。
                    返回 appId 可用于 apigo_app_authorize。
                    """,
            annotations = @McpTool.McpAnnotations(title = "创建调用应用", readOnlyHint = false,
                    destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> appCreate(
            @McpToolParam(description = "应用名称", required = true) String appName,
            @McpToolParam(description = "应用描述", required = false) String appDesc) {
        return support.bindWrite("apigo_app_create", "appName=" + appName, () -> {
            BaseApp app = new BaseApp();
            app.setAppName(support.requireText(appName, "appName"));
            app.setAppDesc(appDesc);
            app.setAppCode(generateAppCode());
            app.setAppKey(randomId());
            app.setAppSecret(randomId());
            appService.addApp(app);

            Map<String, Object> output = McpToolSupport.newResult();
            McpToolSupport.putIfNotNull(output, "appId", app.getAppId());
            McpToolSupport.putIfNotNull(output, "appName", app.getAppName());
            output.put("appCode", app.getAppCode());
            output.put("appKey", app.getAppKey());
            output.put("appSecret", app.getAppSecret());
            output.put("tip", "appSecret 仅本次返回，请立即告知用户保存；后续无法再查询");
            return output;
        });
    }

    @McpTool(name = "apigo_app_authorize",
            description = """
                    为应用授权接口访问权限。
                    默认行为是「合并」：在已有授权基础上追加 apiIds，不会影响其它已授权接口。
                    replaceAll=true 时为「全量覆盖」：应用最终只有 apiIds 里的这些接口，
                    且 apiIds 传空数组表示清空全部授权 —— 该模式会立即影响线上可用性，
                    必须先向用户复述影响范围并取得确认，再以 replaceAll=true 且 confirm=true 调用。
                    注意：授权记录的变更按「当前操作人」维度生效，请保持 MCP 归属用户与平台日常维护者一致。
                    """,
            annotations = @McpTool.McpAnnotations(title = "应用授权接口", readOnlyHint = false,
                    destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> appAuthorize(
            @McpToolParam(description = "应用ID", required = true) Long appId,
            @McpToolParam(description = "要授权的接口ID列表", required = true) List<Long> apiIds,
            @McpToolParam(description = "是否全量覆盖已有授权，默认 false（合并）", required = false) Boolean replaceAll,
            @McpToolParam(description = "确认全量覆盖：replaceAll=true 时必须传 true", required = false) Boolean confirm) {
        return support.bindWrite("apigo_app_authorize", "appId=" + appId
                + " apiIds=" + (apiIds == null ? "[]" : apiIds.toString()), () -> {
            if (appId == null) {
                throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), "必传参数不能为空：appId");
            }
            boolean replace = Boolean.TRUE.equals(replaceAll);
            if (replace) {
                McpToolSupport.requireConfirmed(confirm,
                        "全量覆盖会删除该应用其它已授权的接口（apiIds 为空即清空全部授权）");
            }

            Set<Long> finalIds = new LinkedHashSet<>();
            if (!replace) {
                finalIds.addAll(currentAuthorized(appId).ids());
            }
            if (apiIds != null) {
                finalIds.addAll(apiIds);
            }

            BaseAppApi param = new BaseAppApi();
            param.setAppId(appId);
            param.setApiIds(new ArrayList<>(finalIds));
            apiInfoService.addChooseApi(param);

            // 提交本次的授权集合；二次查询仅用于检测「其它操作人遗留的授权记录」
            List<Long> submitted = new ArrayList<>(finalIds);
            AuthorizedIds actual = currentAuthorized(appId);

            Map<String, Object> output = McpToolSupport.newResult();
            McpToolSupport.putIfNotNull(output, "appId", appId);
            output.put("mode", replace ? "replace" : "merge");
            output.put("authorizedApiIds", submitted);
            output.put("authorizedCount", submitted.size());
            if (actual.truncated()) {
                output.put("warning", "该应用的授权记录超过单次读取上限（" + (AUTH_PAGE_SIZE * MAX_AUTH_PAGES)
                        + " 条），本次合并可能未覆盖全部已有授权，请改用 replaceAll 或人工核对");
            } else if (actual.ids().size() > submitted.size()) {
                output.put("warning", "检测到存在其它操作人创建的授权记录，本次只删除了归属 MCP 用户的记录；"
                        + "如需完全一致，请让 MCP 归属用户与原始授权人保持一致");
            }
            return output;
        });
    }

    @McpTool(name = "apigo_app_apis",
            description = "查询某个应用已授权的接口列表（apiId、接口名、状态）。",
            annotations = @McpTool.McpAnnotations(title = "查询应用已授权接口", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> appApis(
            @McpToolParam(description = "应用ID", required = true) Long appId,
            @McpToolParam(description = "页码，从 1 开始", required = false) Integer pageNum,
            @McpToolParam(description = "每页条数，默认 20，上限 50", required = false) Integer pageSize) {
        return support.bindUser("apigo_app_apis", () -> {
            int size = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 50);
            PageInfo<?> page = apiInfoService.getChooseApi(appId, support.pageNum(pageNum), size);
            List<Map<String, Object>> list = new ArrayList<>();
            if (page != null && page.getList() != null) {
                for (Object item : page.getList()) {
                    if (item instanceof ApiComboBoxVO api) {
                        Map<String, Object> row = McpToolSupport.newResult();
                        McpToolSupport.putIfNotNull(row, "apiId", api.getApiId());
                        McpToolSupport.putIfNotNull(row, "apiName", api.getApiName());
                        McpToolSupport.putIfNotNull(row, "apiStatus", api.getApiStatus());
                        list.add(row);
                    }
                }
            }
            Map<String, Object> output = McpToolSupport.newResult();
            McpToolSupport.putIfNotNull(output, "appId", appId);
            output.put("total", page == null ? 0L : page.getTotal());
            output.put("list", list);
            return output;
        });
    }

    /**
     * 读取应用当前已授权的全部接口ID。
     * <p>
     * 服务层未真正分页（PageHelper 被注释），因此这里循环取足量数据后本地处理；
     * 超过上限会置 {@code truncated}，由调用方转为告警，避免静默丢失授权。
     */
    @SuppressWarnings("unchecked")
    private AuthorizedIds currentAuthorized(Long appId) {
        List<Long> ids = new ArrayList<>();
        long total = 0L;
        boolean truncated = false;
        for (int page = 1; page <= MAX_AUTH_PAGES; page++) {
            PageInfo<ApiComboBoxVO> result = (PageInfo<ApiComboBoxVO>) apiInfoService.getChooseApi(appId, page, AUTH_PAGE_SIZE);
            List<ApiComboBoxVO> list = result == null ? null : result.getList();
            if (list == null || list.isEmpty()) {
                break;
            }
            total = result.getTotal();
            for (ApiComboBoxVO api : list) {
                if (api != null && api.getApiId() != null) {
                    ids.add(api.getApiId());
                }
            }
            if (ids.size() >= total) {
                break;
            }
            if (page == MAX_AUTH_PAGES) {
                truncated = true;
            }
        }
        return new AuthorizedIds(ids, Math.max(total, ids.size()), truncated);
    }

    /**
     * 与控制台 AppCode 校验规则保持一致：首字符为字母，总长 5-25。
     */
    private String generateAppCode() {
        return APP_CODE_PREFIX + randomId().substring(0, 23);
    }

    private String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 应用已授权接口的读取结果。
     */
    private record AuthorizedIds(List<Long> ids, long total, boolean truncated) {
    }
}
