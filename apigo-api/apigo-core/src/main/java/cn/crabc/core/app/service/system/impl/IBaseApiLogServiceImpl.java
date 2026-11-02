package cn.crabc.core.app.service.system.impl;

import cn.crabc.core.app.entity.BaseApiLog;
import cn.crabc.core.app.entity.param.ApiLogParam;
import cn.crabc.core.app.entity.vo.ApiLogDailyTrendVO;
import cn.crabc.core.app.entity.vo.ApiLogGroupCountVO;
import cn.crabc.core.app.entity.vo.ApiLogStatisticsVO;
import cn.crabc.core.app.entity.vo.ApiLogSummaryVO;
import cn.crabc.core.app.mapper.BaseApiLogMapper;
import cn.crabc.core.app.service.system.IBaseApiLogService;
import cn.crabc.core.app.util.ResourceOwnerGuard;
import cn.crabc.core.app.util.UserThreadLocal;
import cn.crabc.core.datasource.util.PageInfo;
import com.github.pagehelper.PageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

/**
 * api日志 服务实现
 *
 * @author yuqf
 */
@Service
public class IBaseApiLogServiceImpl implements IBaseApiLogService {

    private static final Logger log = LoggerFactory.getLogger(IBaseApiLogServiceImpl.class);

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String SUCCESS_RESULT = "success";
    /** 单批删除上限：避免一次事务锁住过多行 */
    private static final int DELETE_BATCH_SIZE = 5000;

    @Autowired
    private BaseApiLogMapper baseApiLogMapper;
    @Autowired
    private ResourceOwnerGuard ownerGuard;

    /**
     * 日志保留天数，0 或负数表示不清理。
     */
    @Value("${crabc.api-log.retention-days:30}")
    private int retentionDays;

    /**
     * 过期日志定时清理。
     * <p>
     * {@code base_api_log} 此前没有任何删除路径，长期运行会无限增长并拖慢监控聚合查询。
     * 分批删除避免大事务；凌晨执行避开高峰。
     */
    @Scheduled(cron = "${crabc.api-log.cleanup-cron:0 30 3 * * ?}")
    public void cleanupExpiredLogs() {
        if (retentionDays <= 0) {
            return;
        }
        String endTime = LocalDate.now().minusDays(retentionDays).atTime(LocalTime.MAX).format(DATE_TIME_FORMATTER);
        try {
            long total = 0;
            int deleted;
            do {
                deleted = baseApiLogMapper.deleteBefore(endTime, DELETE_BATCH_SIZE);
                total += deleted;
            } while (deleted >= DELETE_BATCH_SIZE);
            if (total > 0) {
                log.info("清理过期API日志完成，保留{}天，删除{}条，截止时间：{}", retentionDays, total, endTime);
            }
        } catch (Exception e) {
            log.error("清理过期API日志失败，截止时间：{}", endTime, e);
        }
    }

    @Override
    public Integer addLog(BaseApiLog log) {
        return baseApiLogMapper.insert(log);
    }

    @Override
    public Integer batchAddLog(List<BaseApiLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return 0;
        }
        return baseApiLogMapper.batchInsert(logs);
    }

    @Override
    public PageInfo page(ApiLogParam param) {
        applyOwnerScope(param);
        PageHelper.startPage(param.getPageNum(), param.getPageSize(), false);
        List<BaseApiLog> list = baseApiLogMapper.selectList(param);
        return new PageInfo<>(list, param.getPageNum(), param.getPageSize());
    }

    @Override
    public BaseApiLog logDetail(Long logId) {
        BaseApiLog log = baseApiLogMapper.selectOne(logId);
        // 日志正文可能含请求参数与响应体，详情同样要校验接口归属
        if (log != null && log.getApiId() != null) {
            ownerGuard.assertApi(log.getApiId());
        }
        return log;
    }

    @Override
    public ApiLogSummaryVO summary(ApiLogParam param) {
        ApiLogParam queryParam = param == null ? new ApiLogParam() : param;
        applyOwnerScope(queryParam);
        fillDefaultTimeRange(queryParam);
        return fillSummary(baseApiLogMapper.selectLogSummary(queryParam));
    }

    @Override
    public List<ApiLogDailyTrendVO> dailyTrend(ApiLogParam param) {
        ApiLogParam queryParam = param == null ? new ApiLogParam() : param;
        applyOwnerScope(queryParam);
        fillDefaultTimeRange(queryParam);
        return emptyIfNull(baseApiLogMapper.selectDailyTrend(queryParam));
    }

    @Override
    public List<ApiLogGroupCountVO> statusPie(ApiLogParam param) {
        ApiLogParam queryParam = param == null ? new ApiLogParam() : param;
        applyOwnerScope(queryParam);
        fillDefaultTimeRange(queryParam);
        return emptyIfNull(baseApiLogMapper.selectStatusStats(queryParam));
    }

    @Override
    public List<ApiLogGroupCountVO> topApis(ApiLogParam param) {
        ApiLogParam queryParam = param == null ? new ApiLogParam() : param;
        applyOwnerScope(queryParam);
        fillDefaultTimeRange(queryParam);
        return emptyIfNull(baseApiLogMapper.selectTopApis(queryParam));
    }

    @Override
    public List<ApiLogGroupCountVO> topCostApis(ApiLogParam param) {
        ApiLogParam queryParam = param == null ? new ApiLogParam() : copyParam(param);
        applyOwnerScope(queryParam);
        fillDefaultTimeRange(queryParam);
        // 耗时排行只看成功调用，失败接口可能包含异常等待或降级路径，会干扰真实接口性能判断。
        queryParam.setResult(SUCCESS_RESULT);
        return emptyIfNull(baseApiLogMapper.selectTopCostApis(queryParam));
    }

    @Override
    public List<ApiLogGroupCountVO> topIps(ApiLogParam param) {
        ApiLogParam queryParam = param == null ? new ApiLogParam() : param;
        applyOwnerScope(queryParam);
        fillDefaultTimeRange(queryParam);
        return emptyIfNull(baseApiLogMapper.selectTopIps(queryParam));
    }

    @Override
    public ApiLogStatisticsVO statistics(ApiLogParam param) {
        ApiLogStatisticsVO statistics = new ApiLogStatisticsVO();
        statistics.setSummary(summary(copyParam(param)));
        statistics.setDailyTrend(dailyTrend(copyParam(param)));
        statistics.setStatusPie(statusPie(copyParam(param)));
        statistics.setTopApis(topApis(copyParam(param)));
        statistics.setTopCostApis(topCostApis(copyParam(param)));
        statistics.setTopIps(topIps(copyParam(param)));
        return statistics;
    }

    /**
     * 兼容统计接口会串行调用多个独立统计方法，复制参数避免耗时排行覆盖result后影响其它统计口径。
     */
    private ApiLogParam copyParam(ApiLogParam param) {
        if (param == null) {
            return null;
        }
        ApiLogParam copy = new ApiLogParam();
        copy.setResult(param.getResult());
        copy.setKeyword(param.getKeyword());
        copy.setAppName(param.getAppName());
        copy.setStartTime(param.getStartTime());
        copy.setEndTime(param.getEndTime());
        copy.setPageNum(param.getPageNum());
        copy.setPageSize(param.getPageSize());
        copy.setCreateBy(param.getCreateBy());
        return copy;
    }

    /**
     * 服务层统一写入数据权限范围：任何入口（REST / MCP / 内部调用）都不会遗漏。
     * <p>
     * 管理员为 null 表示不限归属；普通用户只能看到自己创建的接口日志。
     *
     * @param param 查询条件
     */
    private void applyOwnerScope(ApiLogParam param) {
        if (param == null) {
            return;
        }
        param.setCreateBy(UserThreadLocal.isAdmin() ? null : UserThreadLocal.getUserId());
    }

    /**
     * 统计接口默认只看近七天，避免监控页首次打开时对日志表做全量聚合。
     */
    private void fillDefaultTimeRange(ApiLogParam param) {
        if (param.getStartTime() != null && !param.getStartTime().isBlank()
                && param.getEndTime() != null && !param.getEndTime().isBlank()) {
            return;
        }
        LocalDate today = LocalDate.now();
        param.setStartTime(today.minusDays(6).atStartOfDay().format(DATE_TIME_FORMATTER));
        param.setEndTime(today.atTime(LocalTime.MAX).format(DATE_TIME_FORMATTER));
    }

    /**
     * 摘要指标在服务层补齐空值和成功率，前端直接渲染即可。
     */
    private ApiLogSummaryVO fillSummary(ApiLogSummaryVO summary) {
        ApiLogSummaryVO actual = summary == null ? new ApiLogSummaryVO() : summary;
        actual.setTotalCount(defaultLong(actual.getTotalCount()));
        actual.setSuccessCount(defaultLong(actual.getSuccessCount()));
        actual.setFailCount(defaultLong(actual.getFailCount()));
        actual.setAvgCostTime(defaultDouble(actual.getAvgCostTime()));
        actual.setMaxCostTime(defaultLong(actual.getMaxCostTime()));
        if (actual.getTotalCount() == 0) {
            actual.setSuccessRate(0D);
        } else {
            double rate = actual.getSuccessCount() * 100D / actual.getTotalCount();
            actual.setSuccessRate(Math.round(rate * 100D) / 100D);
        }
        return actual;
    }

    /**
     * Mapper无数据时统一返回空集合，避免调用方处理null分支。
     */
    private <T> List<T> emptyIfNull(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * 数值聚合结果可能因空表返回null，统一转为0方便前端显示。
     */
    private Long defaultLong(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 平均耗时等小数聚合结果为空时按0展示。
     */
    private Double defaultDouble(Double value) {
        return value == null ? 0D : value;
    }
}
