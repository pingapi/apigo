package cn.crabc.core.app.api;

import cn.crabc.core.app.entity.BaseApiParam;
import cn.crabc.core.app.entity.dto.ApiInfoDTO;
import cn.crabc.core.app.enums.ResultTypeEnum;
import cn.crabc.core.app.service.core.IBaseDataService;
import cn.crabc.core.app.util.ApiThreadLocal;
import cn.crabc.core.app.util.Result;
import cn.crabc.core.datasource.constant.BaseConstant;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.plugin.DataPluginException;
import cn.crabc.core.plugin.DataPluginExecutor;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * API 接收处理层
 *
 * @author yuqf
 */
@RestController
@RequestMapping("/api/web")
public class ApiServiceController {

    private static final Logger log = LoggerFactory.getLogger(ApiServiceController.class);

    @Autowired
    private IBaseDataService baseDataService;

    @Autowired
    private DataPluginExecutor dataPluginExecutor;

    // 参数安全配置
    private static final int MAX_PARAM_VALUE_LENGTH = 10000; // 单个参数值最大长度
    private static final int MAX_PARAMS_COUNT = 100; // 最大参数数量
    private static final Pattern DANGEROUS_PATTERN = Pattern.compile(
        ".*(<script|javascript:|onerror=|onload=|eval\\(|exec\\(|<iframe).*",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * API GET请求方法
     *
     * @return
     */
    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.DELETE})
    public Result getService(@RequestParam(required = false) Map<String, Object> paramMap, HttpServletResponse response) {
        // 1. 获取API配置信息
        ApiInfoDTO api = ApiThreadLocal.get();
        if (api == null) {
            response.setStatus(400);
            return Result.error(ErrorStatusEnum.API_INVALID.getCode(), ErrorStatusEnum.API_INVALID.getMassage());
        }

        // 2. 参数安全验证
        if (!validateParamsSafety(paramMap)) {
            response.setStatus(400);
            return Result.error(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), "参数包含非法内容");
        }

        // 3. 参数校验
        if (!validateParams(api, paramMap)) {
            response.setStatus(400);
            return Result.error(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), ErrorStatusEnum.PARAM_NOT_FOUNT.getMassage());
        }

        // 4. 执行API
        return executeApi(api, paramMap);
    }

    /**
     * API post请求
     *
     * @param paramMap
     * @param body
     * @return
     */
    @RequestMapping(value = "/**", method = {RequestMethod.POST, RequestMethod.PUT})
    public Result postService(@RequestParam(required = false) Map<String, Object> paramMap, @RequestBody(required = false) Object body, HttpServletResponse response) {
        // 1. 获取API配置信息
        ApiInfoDTO api = ApiThreadLocal.get();
        if (api == null) {
            response.setStatus(400);
            return Result.error(ErrorStatusEnum.API_INVALID.getCode(), ErrorStatusEnum.API_INVALID.getMassage());
        }

        // 2. 合并请求参数
        Map<String, Object> mergedParams = mergeParams(paramMap, body);

        // 3. 参数安全验证
        if (!validateParamsSafety(mergedParams)) {
            response.setStatus(400);
            return Result.error(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), "参数包含非法内容");
        }

        // 4. 参数校验
        if (!checkParams(api.getRequestParams(), mergedParams)) {
            response.setStatus(400);
            return Result.error(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), ErrorStatusEnum.PARAM_NOT_FOUNT.getMassage());
        }

        // 5. 设置分页配置
        mergedParams.put(BaseConstant.PAGE_SETUP, api.getPageSetup());

        // 6. 执行API
        return executeApi(api, mergedParams);
    }

    /**
     * 校验参数
     *
     * @param apiParams
     * @param paramMap
     * @return
     */
    public boolean checkParams(List<BaseApiParam> apiParams, Map<String, Object> paramMap) {
        if (apiParams == null || paramMap == null) {
            return true;
        }

        for (BaseApiParam param : apiParams) {
            String paramName = param.getParamName();
            String paramType = param.getParamType();
            Object value = paramMap.get(paramName);

            // 1. 必填参数校验
            if ("Y".equals(param.getRequired()) && !paramMap.containsKey(paramName)) {
                return false;
            }

            // 2. 参数类型验证和转换
            if (value != null && !"".equals(value)) {
                // Array类型参数处理
                if ("Array".equalsIgnoreCase(paramType)) {
                    String[] values = value.toString().split(",");
                    // 数组元素数量限制，防止超大数组
                    if (values.length > 1000) {
                        return false;
                    }
                    paramMap.put(paramName, Arrays.asList(values));
                }
                // Number类型验证
                else if ("Number".equalsIgnoreCase(paramType) || "Integer".equalsIgnoreCase(paramType)) {
                    try {
                        Integer.parseInt(value.toString());
                    } catch (NumberFormatException e) {
                        return false;
                    }
                }
            } else if ("Y".equals(param.getRequired())) {
                // 必填参数值为空
                return false;
            }
        }
        return true;
    }

    /**
     * 验证请求参数
     */
    private boolean validateParams(ApiInfoDTO api, Map<String, Object> paramMap) {
        if (paramMap == null) {
            return true;
        }

        // page_setup 可能为空（老数据或非页面创建的接口），统一按不分页处理，避免拆箱 NPE
        int pageSetup = api.getPageSetup() == null ? 0 : api.getPageSetup();
        paramMap.put(BaseConstant.PAGE_SETUP, pageSetup);

        return checkParams(api.getRequestParams(), paramMap) &&
               !(pageSetup == 1 && !paramMap.containsKey(BaseConstant.PAGE_NUM));
    }

    /**
     * 参数安全验证：检查参数数量、长度和危险内容
     */
    private boolean validateParamsSafety(Map<String, Object> paramMap) {
        if (paramMap == null || paramMap.isEmpty()) {
            return true;
        }

        // 1. 参数数量限制，防止参数过多导致的DoS攻击
        if (paramMap.size() > MAX_PARAMS_COUNT) {
            return false;
        }

        // 2. 逐个检查参数值
        for (Map.Entry<String, Object> entry : paramMap.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }

            String valueStr = value.toString();

            // 3. 参数长度限制
            if (valueStr.length() > MAX_PARAM_VALUE_LENGTH) {
                return false;
            }

            // 4. 检查危险内容（XSS、脚本注入等）
            if (DANGEROUS_PATTERN.matcher(valueStr).matches()) {
                return false;
            }
        }

        return true;
    }

    /**
     * 合并请求参数
     */
    private Map<String, Object> mergeParams(Map<String, Object> paramMap, Object body) {
        Map<String, Object> mergedParams = new HashMap<>();
        if (paramMap != null) {
            mergedParams.putAll(paramMap);
        }
        if (body instanceof Map) {
            mergedParams.putAll((Map<String, Object>) body);
        }
        return mergedParams;
    }

    /**
     * 执行API调用
     */
    private Result executeApi(ApiInfoDTO api, Map<String, Object> params) {
        if (params == null) {
            params = new HashMap<>();
        }
        // 事务开关属于接口配置的内部执行参数，覆盖同名请求参数以避免外部调用篡改事务边界。
        params.put(BaseConstant.TRANSACTION_ENABLED, normalizeTransactionEnabled(api.getTransactionEnabled()));
        // 脚本超时时间同样按接口配置覆盖，未配置时使用平台默认值。
        params.put(BaseConstant.SCRIPT_TIMEOUT_SECONDS, normalizeScriptTimeout(api.getScriptTimeoutSeconds()));

        // 执行数据查询
        Object data = baseDataService.execute(api.getDatasourceId().toString(), api.getDatasourceType(),
                                            api.getSchemaName(), api.getSqlScript(), params);

        // 应用数据插件链（对 SQL 执行结果进行过滤、重命名、结构转换等后处理）
        try {
            data = dataPluginExecutor.execute(api.getPluginConfig(), data, params);
        } catch (DataPluginException e) {
            // 插件失败需携带接口上下文留痕，便于线上定位；对外响应由全局异常处理器泛化
            log.error("接口[{}]数据插件执行失败，插件：{}", api.getApiId(), e.getPluginKey(), e);
            throw e;
        }

        // 根据返回类型处理结果
        if (ResultTypeEnum.ONE.getName().equals(api.getResultType()) && data instanceof List) {
            List<Object> list = (List<Object>) data;
            return Result.success(list.isEmpty() ? null : list.get(0));
        }
        return Result.success(data);
    }

    /**
     * 事务字段持久化使用1/0，执行层只需要稳定的数字语义。
     */
    private Integer normalizeTransactionEnabled(Integer transactionEnabled) {
        return transactionEnabled == null ? 0 : transactionEnabled;
    }

    /**
     * 未配置脚本超时时回退平台默认值，并限制在允许区间内。
     */
    private Integer normalizeScriptTimeout(Integer scriptTimeoutSeconds) {
        if (scriptTimeoutSeconds == null || scriptTimeoutSeconds <= 0) {
            return BaseConstant.DEFAULT_SCRIPT_TIMEOUT_SECONDS;
        }
        return Math.min(scriptTimeoutSeconds, BaseConstant.MAX_SCRIPT_TIMEOUT_SECONDS);
    }
}
