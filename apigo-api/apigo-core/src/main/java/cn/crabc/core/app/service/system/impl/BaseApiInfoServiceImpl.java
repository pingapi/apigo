package cn.crabc.core.app.service.system.impl;

import cn.crabc.core.app.entity.*;
import cn.crabc.core.app.entity.dto.ApiInfoDTO;
import cn.crabc.core.app.entity.param.ApiInfoParam;
import cn.crabc.core.app.entity.param.ApiRateLimitParam;
import cn.crabc.core.app.entity.vo.ApiComboBoxVO;
import cn.crabc.core.app.entity.vo.ApiInfoVO;
import cn.crabc.core.app.entity.vo.ApiRateLimitVO;
import cn.crabc.core.app.entity.vo.BaseApiInfoVO;
import cn.crabc.core.app.enums.ApiStateEnum;
import cn.crabc.core.app.mapper.BaseApiInfoMapper;
import cn.crabc.core.app.mapper.BaseApiParamMapper;
import cn.crabc.core.app.mapper.BaseAppApiMapper;
import cn.crabc.core.app.mapper.BaseAppMapper;
import cn.crabc.core.app.service.system.IBaseApiInfoService;
import cn.crabc.core.datasource.constant.BaseConstant;
import cn.crabc.core.datasource.util.PageInfo;
import cn.crabc.core.app.util.UserThreadLocal;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import cn.crabc.core.plugin.DataPluginConfigValidator;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * API基本信息 服务实现
 *
 * @author yuqf
 */
@Service
public class BaseApiInfoServiceImpl implements IBaseApiInfoService {

    @Autowired
    private BaseApiInfoMapper apiInfoMapper;
    @Autowired
    private BaseAppMapper baseAppMapper;
    @Autowired
    private BaseAppApiMapper baseAppApiMapper;
    @Autowired
    private BaseApiParamMapper apiParamMapper;
    @Autowired
    @Qualifier("apiCache")
    Cache<String, ApiInfoDTO> apiInfoCache;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private ApiRateLimitService apiRateLimitService;
    @Autowired
    private DataPluginConfigValidator dataPluginConfigValidator;


    @Override
    public ApiInfoDTO getApiInfoCache(String method, String apiPath) {
        ApiInfoDTO apiInfo = apiInfoMapper.getApiDetail(method, apiPath);
        if (apiInfo == null) {
            return null;
        }
        fillTransactionDefault(apiInfo);
        // 应用
        List<BaseApp> appApis = baseAppMapper.selectApiApp(apiInfo.getApiId());
        // 请求参数
        List<BaseApiParam> baseApiParams = apiParamMapper.selectReqParams(apiInfo.getApiId());

        apiInfo.setAppList(appApis == null ? new ArrayList<>() : appApis);
        apiInfo.setRequestParams(baseApiParams == null ? new ArrayList<>() : baseApiParams);
        return apiInfo;
    }

    private void invalidateApiCache(String method, String apiPath) {
        if (method == null || apiPath == null || apiPath.isBlank()) {
            return;
        }
        apiInfoCache.invalidate(IBaseApiInfoService.buildCacheKey(method, apiPath));
    }

    private void invalidateApiCache(BaseApiInfo apiInfo) {
        if (apiInfo == null) {
            return;
        }
        invalidateApiCache(apiInfo.getApiMethod(), apiInfo.getApiPath());
    }

    private BaseApiInfo getApiForCacheInvalidation(Long apiId) {
        if (apiId == null) {
            return null;
        }
        return apiInfoMapper.selectApiById(apiId);
    }

    @Override
    public List<ApiInfoDTO> getApiCache(Long apiId) {
        List<ApiInfoDTO> apiInfos = apiInfoMapper.selectApiDetail(apiId);
        if (apiInfos.isEmpty()) {
            return apiInfos;
        }
        // 应用
        List<BaseApp> appApis = baseAppMapper.selectApiApp(apiId);
        Map<Long, List<BaseApp>> appMap = appApis.stream().collect(Collectors.groupingBy(BaseApp::getApiId));
        // 请求参数
        List<BaseApiParam> baseApiParams = apiParamMapper.selectReqParams(apiId);
        Map<Long, List<BaseApiParam>> paramMap = baseApiParams.stream().collect(Collectors.groupingBy(BaseApiParam::getApiId));
        for (ApiInfoDTO api : apiInfos) {
            fillTransactionDefault(api);
            Long apiIdKey = api.getApiId();
            if (appMap.containsKey(apiIdKey)) {
                api.setAppList(appMap.get(apiIdKey));
            }
            if (paramMap.containsKey(apiIdKey)) {
                api.setRequestParams(paramMap.get(apiIdKey));
            }
        }
        return apiInfos;
    }

    @Override
    public PageInfo<BaseApiInfo> getApiPage(String apiName, String devType, int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<BaseApiInfo> list = apiInfoMapper.selectList(apiName, devType);
        return new PageInfo<>(list, pageNum, pageSize);
    }

    @Override
    public List<BaseApiInfo> getApiList(String apiName) {
        return apiInfoMapper.selectList(apiName, null);
    }

    @Override
    public List<ApiComboBoxVO> getApiListGroup(Integer groupId) {
        return apiInfoMapper.selectApiGroup(groupId, null);
    }

    @Override
    public List<ApiComboBoxVO> getApiListUser(String userId) {
        return apiInfoMapper.selectApiGroup(null, userId);
    }

    @Override
    public Boolean checkApiPath(Long apiId, String apiPath, String method) {
        Integer count = apiInfoMapper.checkApiPath(apiId, apiPath, method);
        return count > 0 ? true : false;
    }

    @Override
    public ApiRateLimitVO getRateLimit(Long apiId) {
        BaseApiInfo apiInfo = apiInfoMapper.selectApiById(apiId);
        if (apiInfo == null) {
            throw new CustomException(ErrorStatusEnum.API_NOT_FOUNT.getCode(), ErrorStatusEnum.API_NOT_FOUNT.getMassage());
        }
        ApiRateLimitVO result = new ApiRateLimitVO();
        result.setApiId(apiInfo.getApiId());
        result.setApiName(apiInfo.getApiName());
        result.setApiMethod(apiInfo.getApiMethod());
        result.setApiPath(apiInfo.getApiPath());
        result.setLimitCount(apiInfo.getRateLimitCount());
        fillWindowDisplay(apiInfo.getRateLimitWindowSeconds(), result);
        return result;
    }

    @Override
    public Integer saveRateLimit(ApiRateLimitParam param) {
        if (param == null || param.getApiId() == null) {
            throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), "接口ID不能为空");
        }
        BaseApiInfo oldApi = getApiForCacheInvalidation(param.getApiId());
        if (oldApi == null) {
            throw new CustomException(ErrorStatusEnum.API_NOT_FOUNT.getCode(), ErrorStatusEnum.API_NOT_FOUNT.getMassage());
        }
        BaseApiInfo apiInfo = new BaseApiInfo();
        apiInfo.setApiId(param.getApiId());
        apiInfo.setUpdateTime(new Date());
        apiInfo.setUpdateBy(UserThreadLocal.getUserId());
        if (!isClearRateLimit(param)) {
            apiInfo.setRateLimitWindowSeconds(parseWindowSeconds(param));
            apiInfo.setRateLimitCount(parseLimitCount(param));
        }
        Integer result = apiInfoMapper.updateRateLimit(apiInfo);
        invalidateApiCache(oldApi);
        apiRateLimitService.invalidate(oldApi.getApiMethod(), oldApi.getApiPath());
        return result;
    }

    @Override
    public BaseApiInfoVO getApiDetail(Long apiId) {
        BaseApiInfoVO apiInfo = apiInfoMapper.selectBaseApi(apiId);
        fillTransactionDefault(apiInfo);
        return apiInfo;
    }

    @Override
    public ApiInfoVO getApiInfo(Long apiId) {
        ApiInfoVO result = new ApiInfoVO();
        BaseApiInfo baseApiInfo = apiInfoMapper.selectApiById(apiId);
        if (baseApiInfo == null) {
            return result;
        }
        fillTransactionDefault(baseApiInfo);
        if (ApiStateEnum.RELEASE.getName().equals(baseApiInfo.getApiStatus()) && hasDraft(baseApiInfo)) {
            result = buildApiInfoVO(readDraftContent(baseApiInfo.getDraftContent()), true);
            result.getBaseInfo().setApiId(apiId);
            result.getBaseInfo().setApiStatus(ApiStateEnum.RELEASE.getName());
            result.getBaseInfo().setEnabled(baseApiInfo.getEnabled());
            return result;
        }
        BaseApiSql baseApiSql = new BaseApiSql();
        BeanUtils.copyProperties(baseApiInfo, baseApiSql);
        result.setSqlInfo(baseApiSql);
        baseApiInfo.setPageSetup(baseApiSql.getPageSetup());
        result.setBaseInfo(baseApiInfo);
        result.setQueryEngine("jdbc");
        List<BaseApiParam> baseApiParams = apiParamMapper.selectList(apiId);
        if (!baseApiParams.isEmpty()) {
            Map<String, List<BaseApiParam>> map = baseApiParams.stream().collect(Collectors.groupingBy(BaseApiParam::getParamModel));
            result.setRequestParam(map.get("request") == null ? new ArrayList<>() : map.get("request"));
            result.setResponseParam(map.get("response") == null ? new ArrayList<>() : map.get("response"));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addApiInfo(ApiInfoParam params) {
        validatePluginConfig(params.getBaseInfo());
        validateScriptTimeout(params.getBaseInfo());
        Date date = new Date();
        BaseApiInfo api = params.getBaseInfo();
        BaseApiSql sql = params.getSqlInfo();
        api.setApiStatus(ApiStateEnum.EDIT.getName());
        api.setEnabled(0);
        api.setApiType("SQL");
        api.setDatasourceId(sql.getDatasourceId());
        api.setSchemaName(sql.getSchemaName());
        api.setTableName(sql.getTableName());
        api.setDatasourceType(sql.getDatasourceType());
        api.setSqlScript(sql.getSqlScript());
        fillTransactionDefault(api);
        fillScriptTimeoutDefault(api);
        api.setCreateTime(date);
        api.setUpdateTime(date);
        api.setCreateBy(UserThreadLocal.getUserId());
        if (api.getGroupId() == null) {
            api.setGroupId(1);
        }
        apiInfoMapper.insertApiInfo(api);
        // 参数
        this.insertApiParams(params.getRequestParam(),params.getResponseParam(),api.getApiId());
        return api.getApiId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long updateApiInfo(ApiInfoParam params) {
        validatePluginConfig(params.getBaseInfo());
        validateScriptTimeout(params.getBaseInfo());
        Long apiId = params.getBaseInfo().getApiId();
        Integer count = apiInfoMapper.countApi(apiId);
        if (count == 0) {
            throw new CustomException(ErrorStatusEnum.API_NOT_FOUNT.getCode(), ErrorStatusEnum.API_NOT_FOUNT.getMassage());
        }
        BaseApiInfo previousApi = getApiForCacheInvalidation(apiId);
        if (ApiStateEnum.RELEASE.getName().equals(previousApi.getApiStatus())) {
            saveDraftContent(apiId, params, previousApi);
            return apiId;
        }
        BaseApiInfo api = params.getBaseInfo();
        BaseApiSql sql = params.getSqlInfo();
        Date updateTime = new Date();
        api.setApiStatus(ApiStateEnum.EDIT.getName());
        api.setEnabled(0);
        api.setDatasourceId(sql.getDatasourceId());
        api.setSchemaName(sql.getSchemaName());
        api.setTableName(sql.getTableName());
        api.setDatasourceType(sql.getDatasourceType());
        api.setSqlScript(sql.getSqlScript());
        fillTransactionDefault(api);
        fillScriptTimeoutDefault(api);
        api.setUpdateTime(updateTime);
        api.setUpdateBy(UserThreadLocal.getUserId());
        apiInfoMapper.updateApiInfo(api);
        apiParamMapper.delete(apiId);
        // 参数
        this.insertApiParams(params.getRequestParam(),params.getResponseParam(), api.getApiId());
        invalidateApiCache(previousApi);
        invalidateApiCache(api);
        return apiId;
    }

    @Override
    public Integer updateApiState(Long apiId, String status, Integer enabled) {
        Integer count = apiInfoMapper.countApi(apiId);
        if (count == 0) {
            throw new CustomException(ErrorStatusEnum.API_NOT_FOUNT.getCode(), ErrorStatusEnum.API_NOT_FOUNT.getMassage());
        }
        BaseApiInfo previousApi = getApiForCacheInvalidation(apiId);
        BaseApiInfo baseApiInfo = new BaseApiInfo();
        baseApiInfo.setApiId(apiId);
        baseApiInfo.setUpdateTime(new Date());
        baseApiInfo.setUpdateBy(UserThreadLocal.getUserId());
        if (status != null && !"".equals(status)) {
            baseApiInfo.setApiStatus(status);
        }
        if (enabled != null) {
            baseApiInfo.setEnabled(enabled);
        }
        apiInfoMapper.updateApiState(baseApiInfo);
        invalidateApiCache(previousApi);
        return 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer deleteApi(Long apiId, String userId) {
        BaseApiInfo previousApi = getApiForCacheInvalidation(apiId);
        Integer result = apiInfoMapper.deleteApiInfo(apiId, userId);
        invalidateApiCache(previousApi);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long apiPublish(ApiInfoParam apiInfoParam) {
        Long apiId = apiInfoParam.getBaseInfo().getApiId();
        BaseApiInfo oldApiInfo = apiInfoMapper.selectApiById(apiId);
        if (oldApiInfo == null) {
            throw new CustomException(ErrorStatusEnum.API_NOT_FOUNT.getCode(), ErrorStatusEnum.API_NOT_FOUNT.getMassage());
        }
        BaseApiInfo previousApi = copyApiInfo(oldApiInfo);
        boolean publishDraft = ApiStateEnum.RELEASE.getName().equals(oldApiInfo.getApiStatus()) && hasDraft(oldApiInfo);
        ApiInfoParam publishParam = publishDraft ? readDraftContent(oldApiInfo.getDraftContent()) : apiInfoParam;
        Date updateTime = new Date();
        BaseApiInfo api = publishDraft ? publishParam.getBaseInfo() : oldApiInfo;
        BaseApiSql sql = publishDraft ? publishParam.getSqlInfo() : null;
        api.setApiId(apiId);
        api.setEnabled(1);
        api.setParentId(0L);
        api.setReleaseTime(updateTime);
        api.setUpdateTime(updateTime);
        api.setUpdateBy(UserThreadLocal.getUserId());
        api.setApiStatus(ApiStateEnum.RELEASE.getName());
        if (sql != null) {
            api.setDatasourceId(sql.getDatasourceId());
            api.setSchemaName(sql.getSchemaName());
            api.setTableName(sql.getTableName());
            api.setDatasourceType(sql.getDatasourceType());
            api.setSqlScript(sql.getSqlScript());
            api.setPageSetup(sql.getPageSetup());
        }
        fillTransactionDefault(api);
        fillScriptTimeoutDefault(api);
        apiInfoMapper.updateApiInfo(api);
        if (publishDraft) {
            apiParamMapper.delete(apiId);
            this.insertApiParams(publishParam.getRequestParam(), publishParam.getResponseParam(), apiId);
            apiInfoMapper.updateDraftContent(apiId, null, UserThreadLocal.getUserId());
        }
        invalidateApiCache(previousApi);
        invalidateApiCache(api);
        return apiId;
    }

    @Override
    public PageInfo getNotChooseApi(Long appId, Integer pageNum, Integer pageSize) {
        //PageHelper.startPage(pageNum, pageSize);
        List<ApiComboBoxVO> allApi = apiInfoMapper.selectApiApp(null);
        List<ApiComboBoxVO> appApis = apiInfoMapper.selectApiApp(appId);
        allApi.removeAll(appApis);
        return new PageInfo<>(allApi, pageNum, pageSize);
    }

    @Override
    public PageInfo getChooseApi(Long appId, Integer pageNum, Integer pageSize) {
        //PageHelper.startPage(pageNum, pageSize);
        List<ApiComboBoxVO> list = apiInfoMapper.selectApiApp(appId);
        return new PageInfo<>(list, pageNum, pageSize);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer addChooseApi(BaseAppApi appApi) {
        String userId = UserThreadLocal.getUserId();
        baseAppApiMapper.delete(appApi.getAppId(), userId);

        if (appApi.getApiIds() == null || appApi.getApiIds().size() == 0) {
            return 1;
        }
        Date time = new Date();
        List<BaseAppApi> list = new ArrayList<>();
        for (Long apiId : appApi.getApiIds()) {
            BaseAppApi a = new BaseAppApi();
            a.setAppId(appApi.getAppId());
            a.setApiId(apiId);
            a.setCreateBy(userId);
            a.setCreateTime(time);
            list.add(a);
        }
        baseAppApiMapper.insert(list);
        apiInfoCache.invalidateAll();
        return 1;
    }

    @Override
    public Integer destroyApiInfo(Long apiId) {
        String userId = UserThreadLocal.getUserId();
        BaseApiInfo previousApi = getApiForCacheInvalidation(apiId);

        BaseApiInfo baseApiInfo = new BaseApiInfo();
        baseApiInfo.setUpdateTime(new Date());
        baseApiInfo.setUpdateBy(userId);
        baseApiInfo.setApiStatus(ApiStateEnum.DESTROY.getName());
        baseApiInfo.setApiId(apiId);
        apiInfoMapper.updateApiState(baseApiInfo);
        invalidateApiCache(previousApi);
        return 1;
    }

    /**
     * api信息历史发布版本存档
     *
     * @param apiInfo
     */
    public void insertHistory(BaseApiInfo apiInfo) {
        Long apiId = apiInfo.getApiId();
        Date updateTime = new Date();
        apiInfo.setApiStatus(ApiStateEnum.HISTORY.getName());
        apiInfo.setParentId(apiId);
        apiInfo.setApiPath(apiInfo.getApiPath() + "/" + apiInfo.getVersion());
        apiInfo.setUpdateTime(updateTime);
        apiInfo.setApiId(null);
        apiInfoMapper.insertApiInfo(apiInfo);
    }

    /**
     * 新增API参数
     * @param requestParams
     * @param responseParams
     */
    private void insertApiParams(List<BaseApiParam> requestParams, List<BaseApiParam> responseParams, Long apiId){
        List<BaseApiParam> params = new ArrayList<>();
        if (requestParams != null && requestParams.size() > 0) {
            params.addAll(requestParams);
        }
        if (responseParams != null && responseParams.size() > 0) {
            params.addAll(responseParams);
        }
        if (params.size() > 0) {
            apiParamMapper.insertBatch(params,apiId);
        }
    }

    private boolean hasDraft(BaseApiInfo apiInfo) {
        return apiInfo.getDraftContent() != null && !apiInfo.getDraftContent().isBlank();
    }

    private void saveDraftContent(Long apiId, ApiInfoParam params, BaseApiInfo publishedApi) {
        BaseApiInfo draftBaseInfo = params.getBaseInfo();
        draftBaseInfo.setApiId(apiId);
        draftBaseInfo.setApiStatus(ApiStateEnum.RELEASE.getName());
        draftBaseInfo.setEnabled(publishedApi.getEnabled());
        fillTransactionDefault(draftBaseInfo);
        fillScriptTimeoutDefault(draftBaseInfo);
        if (params.getSqlInfo() != null) {
            params.getSqlInfo().setApiId(apiId);
        }
        try {
            apiInfoMapper.updateDraftContent(apiId, jsonMapper.writeValueAsString(params), UserThreadLocal.getUserId());
        } catch (Exception e) {
            throw new CustomException(ErrorStatusEnum.SYSTEM_ERROR.getCode(), "暂存接口信息失败");
        }
    }

    private ApiInfoParam readDraftContent(String draftContent) {
        try {
            return jsonMapper.readValue(draftContent, ApiInfoParam.class);
        } catch (Exception e) {
            throw new CustomException(ErrorStatusEnum.SYSTEM_ERROR.getCode(), "读取暂存接口信息失败");
        }
    }

    private ApiInfoVO buildApiInfoVO(ApiInfoParam params, boolean hasDraft) {
        ApiInfoVO result = new ApiInfoVO();
        BaseApiInfo baseInfo = params.getBaseInfo() == null ? new BaseApiInfo() : params.getBaseInfo();
        fillTransactionDefault(baseInfo);
        result.setBaseInfo(baseInfo);
        result.setSqlInfo(params.getSqlInfo() == null ? new BaseApiSql() : params.getSqlInfo());
        result.setRequestParam(params.getRequestParam() == null ? new ArrayList<>() : params.getRequestParam());
        result.setResponseParam(params.getResponseParam() == null ? new ArrayList<>() : params.getResponseParam());
        result.setQueryEngine(params.getQueryEngine() == null ? "jdbc" : params.getQueryEngine());
        result.setHasDraft(hasDraft);
        return result;
    }

    /**
     * 事务开关对旧数据和旧草稿保持向后兼容：缺省按关闭处理，避免空值被执行层误判。
     *
     * @param apiInfo API基本信息
     */
    private void fillTransactionDefault(BaseApiInfo apiInfo) {
        if (apiInfo != null && apiInfo.getTransactionEnabled() == null) {
            apiInfo.setTransactionEnabled(0);
        }
    }

    /**
     * 脚本超时时间只在显式配置时校验，空值或0表示沿用平台默认。
     *
     * @param apiInfo API基本信息
     */
    private void validateScriptTimeout(BaseApiInfo apiInfo) {
        if (apiInfo == null || apiInfo.getScriptTimeoutSeconds() == null || apiInfo.getScriptTimeoutSeconds() == 0) {
            return;
        }
        Integer timeout = apiInfo.getScriptTimeoutSeconds();
        if (timeout < BaseConstant.MIN_SCRIPT_TIMEOUT_SECONDS || timeout > BaseConstant.MAX_SCRIPT_TIMEOUT_SECONDS) {
            throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(),
                "脚本超时时间需在" + BaseConstant.MIN_SCRIPT_TIMEOUT_SECONDS
                    + "-" + BaseConstant.MAX_SCRIPT_TIMEOUT_SECONDS + "秒之间");
        }
    }

    /**
     * 落库统一用0表示未配置，保证清空超时时间后更新语句也能覆盖旧值。
     *
     * @param apiInfo API基本信息
     */
    private void fillScriptTimeoutDefault(BaseApiInfo apiInfo) {
        if (apiInfo != null && apiInfo.getScriptTimeoutSeconds() == null) {
            apiInfo.setScriptTimeoutSeconds(0);
        }
    }

    /**
     * 数据插件配置校验：拦截非法配置，避免"保存成功但运行时静默失效"
     *
     * @param api 接口基本信息
     */
    private void validatePluginConfig(BaseApiInfo api) {
        if (api == null) {
            return;
        }
        try {
            dataPluginConfigValidator.validate(api.getPluginConfig());
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), e.getMessage());
        }
    }

    /**
     * 限流弹框允许三个字段同时为空，表示清空当前接口限流配置。
     */
    private boolean isClearRateLimit(ApiRateLimitParam param) {
        return param.getWindowValue() == null
                && (param.getWindowUnit() == null || param.getWindowUnit().isBlank())
                && param.getLimitCount() == null;
    }

    /**
     * 限流窗口统一落库为秒，避免执行链路反复处理单位换算。
     */
    private Integer parseWindowSeconds(ApiRateLimitParam param) {
        if (param.getWindowValue() == null || param.getWindowValue() <= 0
                || param.getWindowUnit() == null || param.getWindowUnit().isBlank()) {
            throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), "限流时间窗口不能为空");
        }
        String unit = param.getWindowUnit().toUpperCase();
        return switch (unit) {
            case "SECOND" -> param.getWindowValue();
            case "MINUTE" -> param.getWindowValue() * 60;
            case "HOUR" -> param.getWindowValue() * 3600;
            default -> throw new CustomException(ErrorStatusEnum.FORBID_OPERATE.getCode(), "限流时间单位无效");
        };
    }

    /**
     * 请求次数必须为正数，窗口和次数任一缺失都不能形成有效限流规则。
     */
    private Integer parseLimitCount(ApiRateLimitParam param) {
        if (param.getLimitCount() == null || param.getLimitCount() <= 0) {
            throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), "限流请求次数不能为空");
        }
        return param.getLimitCount();
    }

    /**
     * 回显时优先使用能整除的最大常用单位，保持页面展示简洁。
     */
    private void fillWindowDisplay(Integer windowSeconds, ApiRateLimitVO result) {
        if (windowSeconds == null || windowSeconds <= 0) {
            return;
        }
        if (windowSeconds % 3600 == 0) {
            result.setWindowValue(windowSeconds / 3600);
            result.setWindowUnit("HOUR");
        } else if (windowSeconds % 60 == 0) {
            result.setWindowValue(windowSeconds / 60);
            result.setWindowUnit("MINUTE");
        } else {
            result.setWindowValue(windowSeconds);
            result.setWindowUnit("SECOND");
        }
    }

    /**
     * 详情VO直接返回给前端，旧接口没有事务字段时统一回填关闭状态。
     *
     * @param apiInfo API详情VO
     */
    private void fillTransactionDefault(BaseApiInfoVO apiInfo) {
        if (apiInfo != null && apiInfo.getTransactionEnabled() == null) {
            apiInfo.setTransactionEnabled(0);
        }
    }

    private BaseApiInfo copyApiInfo(BaseApiInfo apiInfo) {
        BaseApiInfo copy = new BaseApiInfo();
        BeanUtils.copyProperties(apiInfo, copy);
        return copy;
    }
}
