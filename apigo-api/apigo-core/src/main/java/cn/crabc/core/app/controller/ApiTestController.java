package cn.crabc.core.app.controller;

import cn.crabc.core.app.entity.param.ApiTestParam;
import cn.crabc.core.app.entity.vo.ApiInfoVO;
import cn.crabc.core.app.entity.vo.PreviewVO;
import cn.crabc.core.app.enums.ResultTypeEnum;
import cn.crabc.core.app.service.core.IBaseDataService;
import cn.crabc.core.app.service.system.IBaseApiInfoService;
import cn.crabc.core.app.util.ResourceOwnerGuard;
import cn.crabc.core.app.util.Result;
import cn.crabc.core.datasource.constant.BaseConstant;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import cn.crabc.core.plugin.DataPluginException;
import cn.crabc.core.plugin.DataPluginExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * API运行和测试
 *
 * @author yuqf
 */
@RestController
@RequestMapping("/api/box/sys/test")
public class ApiTestController {

    private static final Logger log = LoggerFactory.getLogger(ApiTestController.class);

    @Autowired
    private IBaseDataService baseDataService;
    @Autowired
    private IBaseApiInfoService apiInfoService;
    @Autowired
    private ResourceOwnerGuard ownerGuard;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private DataPluginExecutor dataPluginExecutor;

    /**
     * 运行预览
     *
     * @param api
     * @return
     */
    @PostMapping("/running")
    public Result runApiSql(@RequestBody ApiTestParam api) {
        if (api.getDatasourceId() == null) {
            return Result.error(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), ErrorStatusEnum.PARAM_NOT_FOUNT.getMassage());
        }
        // 预览只能跑自己的数据源：否则任意登录用户可对他人库执行任意 SQL
        this.requireOwnedDatasource(api.getDatasourceId());
        // 运行预览与在线调用保持一致，按接口配置的脚本超时时间执行
        PreviewVO previewVO = baseDataService.sqlPreview(api.getDatasourceId(), api.getDatasourceType(),
            api.getSchemaName(), api.getSqlScript(), resolveScriptTimeout(api));
        // 应用数据插件链：开发页运行预览同样走插件，字段变化时重建 metadata
        applyPluginToPreview(previewVO, api.getPluginConfig());
        return Result.success(previewVO);
    }

    /**
     * 对运行预览结果应用数据插件，并依据处理后的行重建 metadata 列名
     */
    @SuppressWarnings("unchecked")
    private void applyPluginToPreview(PreviewVO previewVO, Object pluginConfig) {
        if (previewVO == null || previewVO.getData() == null) {
            return;
        }
        Object processed = dataPluginExecutor.execute(
                normalizePluginConfig(pluginConfig), previewVO.getData(), new HashMap<>());
        List<Map<String, Object>> rows;
        if (processed instanceof List<?> list) {
            rows = (List<Map<String, Object>>) list;
        } else if (processed instanceof Map<?, ?> map) {
            // 单对象插件命中：包装为单行结果，避免预览仍展示插件前的原始数据
            rows = List.of((Map<String, Object>) map);
        } else if (processed == null) {
            // 单对象插件未命中（返回 null）：结果为空
            rows = List.of();
        } else {
            // 非行集合结果（如 DML 影响行数、已启用的结构转换异常分支）保持原样
            return;
        }
        previewVO.setData(rows);
        // 依据处理后的行重建列名（保持首行字段顺序）
        Set<String> metadata = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            metadata.addAll(row.keySet());
        }
        previewVO.setMetadata(metadata);
    }

    /**
     * 在线测试API
     *
     * @param params
     * @return
     */
    @PostMapping("/verify/{apiId}")
    public Result testApiSql(@PathVariable Long apiId, @RequestBody ApiTestParam params) throws Exception {
        // 参数校验
        if (params.getDatasourceId() == null) {
            return Result.error(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), ErrorStatusEnum.PARAM_NOT_FOUNT.getMassage());
        }
        // 接口与数据源都必须是当前用户的资源
        ownerGuard.assertApi(apiId);
        this.requireOwnedDatasource(params.getDatasourceId());
        
        // 设置默认数据源类型
        params.setDatasourceType(params.getDatasourceType() == null ? "mysql" : params.getDatasourceType());

        try {
            // 构建参数Map
            Map<String, Object> paramsMap = buildParamsMap(params);
            // 测试执行与线上调用保持一致，按接口配置的脚本超时时间执行
            paramsMap.put(BaseConstant.SCRIPT_TIMEOUT_SECONDS, resolveScriptTimeout(apiId, params));
            
            // 执行SQL
            Object data = baseDataService.execute(
                params.getDatasourceId(),
                params.getDatasourceType(),
                params.getSchemaName(),
                params.getSqlScript(),
                paramsMap
            );

            // 应用数据插件链
            data = dataPluginExecutor.execute(normalizePluginConfig(params.getPluginConfig()), data, paramsMap);

            // 处理返回结果
            return Result.success(formatResultData(data, params.getResultType()));
            
        } catch (DataPluginException e) {
            // 插件失败场景：返回具体插件标识便于开发定位，文案不含任何行数据值
            return Result.error(50020, e.getMessage());
        } catch (Exception e) {
            if(e instanceof CustomException) {
                CustomException ex = (CustomException) e;
                if (ex.getCode() == ErrorStatusEnum.API_SCRIPT_TIMEOUT.getCode()) {
                    return Result.error(ex.getCode(), ex.getMsg());
                }
                return Result.error("测试异常，请检查参数或者SQL是否正常！" +ex.getMsg());
            }
            return Result.error("测试异常，请检查参数或者SQL是否正常！");
        }
    }

    /**
     * 校验测试所用数据源的归属：普通用户只能用自己创建的数据源，管理员可跨租户。
     *
     * @param datasourceId 数据源ID（字符串）
     */
    private void requireOwnedDatasource(String datasourceId) {
        try {
            ownerGuard.assertDataSource(Integer.valueOf(datasourceId.trim()));
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), "数据源ID不合法：" + datasourceId);
        }
    }

    /**
     * 运行预览入口没有缓存上下文，按页面当前设置或接口配置解析超时时间。
     */
    private int resolveScriptTimeout(ApiTestParam api) {
        return resolveScriptTimeout(api.getApiId(), api);
    }

    /**
     * 超时时间优先级：页面当前设置（未保存的修改也能生效）> 接口已保存配置 > 平台默认值。
     *
     * @param apiId  接口ID，可能为空（新建未保存的接口）
     * @param params 请求参数，可能携带页面当前输入的脚本超时时间
     * @return 生效的脚本超时时间（秒）
     */
    private int resolveScriptTimeout(Long apiId, ApiTestParam params) {
        Integer requestTimeout = params == null ? null : params.getScriptTimeoutSeconds();
        if (requestTimeout != null && requestTimeout >= BaseConstant.MIN_SCRIPT_TIMEOUT_SECONDS) {
            return Math.min(requestTimeout, BaseConstant.MAX_SCRIPT_TIMEOUT_SECONDS);
        }
        if (apiId == null) {
            return BaseConstant.DEFAULT_SCRIPT_TIMEOUT_SECONDS;
        }
        try {
            ApiInfoVO apiInfo = apiInfoService.getApiInfo(apiId);
            if (apiInfo != null && apiInfo.getBaseInfo() != null
                && apiInfo.getBaseInfo().getScriptTimeoutSeconds() != null
                && apiInfo.getBaseInfo().getScriptTimeoutSeconds() > 0) {
                return Math.min(apiInfo.getBaseInfo().getScriptTimeoutSeconds(),
                    BaseConstant.MAX_SCRIPT_TIMEOUT_SECONDS);
            }
        } catch (Exception e) {
            log.warn("读取接口超时配置失败，按平台默认值执行，apiId：{}", apiId, e);
        }
        return BaseConstant.DEFAULT_SCRIPT_TIMEOUT_SECONDS;
    }

    /**
     * 构建运行时参数Map，供SQL执行层使用。
     * body 参数来自 bodyData（页面Body编辑器，可能是JSON字符串或对象），
     * query 参数来自 requestParams/requestParam 数组中 paramPosition=query 的项。
     */
    private Map<String, Object> buildParamsMap(ApiTestParam params) throws Exception {
        Map<String, Object> paramsMap = new HashMap<>();

        // 1. 解析 body 参数：bodyData 可能是 JSON 字符串、双重编码字符串或对象
        Object bodyData = params.getBodyData();
        if (bodyData instanceof String) {
            String bodyStr = (String) bodyData;
            // 兼容双重序列化：bodyData 可能以引号开头（JSON 字符串字面量），先剥掉外层引号还原 JSON 文本
            if (bodyStr.startsWith("\"") && bodyStr.endsWith("\"")) {
                bodyStr = jsonMapper.readValue(bodyStr, String.class);
            }
            if (bodyStr.startsWith("{")) {
                Map<String, Object> bodyMap = jsonMapper.readValue(bodyStr, HashMap.class);
                if (bodyMap != null) {
                    paramsMap.putAll(bodyMap);
                }
            } else if (bodyStr.startsWith("[")) {
                List<?> bodyList = jsonMapper.readValue(bodyStr, List.class);
                paramsMap.put("list", bodyList);
            }
        } else if (bodyData instanceof Map) {
            paramsMap.putAll((Map<String, Object>) bodyData);
        } else if (bodyData instanceof List) {
            paramsMap.put("list", bodyData);
        }

        // 2. 解析 requestParams：
        //    - Map 形式：可能是已组装好的键值对（如 {"list":["1","2"],"pageSetup":0}），直接合并；
        //    - List 形式：参数定义数组，仅取 paramPosition=query 的项，body/header 参数值由 bodyData/请求头单独注入
        Object requestParams = params.getRequestParams();
        if (requestParams instanceof Map) {
            Map<String, Object> requestMap = (Map<String, Object>) requestParams;
            // 单个参数定义（含 paramName/value 键）也按定义处理，否则视为键值对直接合并
            if (requestMap.containsKey("paramName") && requestMap.containsKey("value")) {
                Object name = requestMap.get("paramName");
                Object value = requestMap.get("value");
                if (name != null && value != null && !"".equals(value)) {
                    paramsMap.put(name.toString(), value);
                }
            } else {
                paramsMap.putAll(requestMap);
            }
        } else if (requestParams instanceof List) {
            List<Map<String, Object>> paramsList = (List<Map<String, Object>>) requestParams;
            for (Map<String, Object> entry : paramsList) {
                String position = entry.get("paramPosition") == null
                    ? "query" : String.valueOf(entry.get("paramPosition"));
                // 跳过 body/header 位置参数，避免无 value 的 Array 类型被误判为必填失败
                if (!"query".equalsIgnoreCase(position)) {
                    continue;
                }
                Object name = entry.get("paramName");
                if (name == null) {
                    continue;
                }
                Object value = entry.get("value");
                if (value == null || "".equals(value)) {
                    continue;
                }
                // Array 类型做逗号分隔转换
                String paramType = String.valueOf(entry.get("paramType"));
                if ("Array".equalsIgnoreCase(paramType) && !(value instanceof List)) {
                    value = Arrays.asList(value.toString().split(","));
                }
                paramsMap.put(name.toString(), value);
            }
        }

        // 3. 兼容旧字段 queryParam（Map 形式）
        Map<String, Object> queryParam = params.getQueryParam();
        if (queryParam != null && !queryParam.isEmpty()) {
            paramsMap.putAll(queryParam);
        }

        // 分页开关
        if (params.getPageSetup() != null) {
            paramsMap.put("pageSetup", params.getPageSetup());
        }
        // 事务开关只由测试弹窗显式传入，开发页运行/预览不进入多脚本执行入口。
        paramsMap.put(BaseConstant.TRANSACTION_ENABLED, normalizeTransactionEnabled(params.getTransactionEnabled()));
        return paramsMap;
    }

    /**
     * 测试参数兼容旧前端请求，未传事务开关时按关闭处理。
     */
    private Integer normalizeTransactionEnabled(Integer transactionEnabled) {
        return transactionEnabled == null ? 0 : transactionEnabled;
    }

    /**
     * 归一化数据插件配置：兼容 JSON 字符串（开发页原样透传）与已反序列化对象（直接传对象）
     *
     * @param pluginConfig 插件配置，可能为 String / Map / List / null
     * @return 配置 JSON 字符串；为空返回 null
     */
    private String normalizePluginConfig(Object pluginConfig) {
        if (pluginConfig == null) {
            return null;
        }
        if (pluginConfig instanceof String str) {
            return str.isBlank() ? null : str;
        }
        try {
            return jsonMapper.writeValueAsString(pluginConfig);
        } catch (Exception e) {
            throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), "数据插件配置格式不正确");
        }
    }

    /**
     * 格式化返回数据
     */
    private String formatResultData(Object data, String resultType) {
        if (ResultTypeEnum.ONE.getName().equals(resultType) && data instanceof List) {
            List<Object> list = (List<Object>) data;
            return jsonMapper.writeValueAsString(Result.success(list.isEmpty() ? null : list.get(0)));
        }
        return jsonMapper.writeValueAsString(Result.success(data));
    }
}
