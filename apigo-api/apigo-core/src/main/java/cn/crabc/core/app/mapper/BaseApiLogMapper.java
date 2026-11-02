package cn.crabc.core.app.mapper;

import cn.crabc.core.app.entity.BaseApiLog;
import cn.crabc.core.app.entity.param.ApiLogParam;
import cn.crabc.core.app.entity.vo.ApiLogDailyTrendVO;
import cn.crabc.core.app.entity.vo.ApiLogGroupCountVO;
import cn.crabc.core.app.entity.vo.ApiLogSummaryVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * API访问日志 Mapper接口
 *
 * @author yuqf
 */
@Mapper
public interface BaseApiLogMapper {

    /**
     * 添加日志
     *
     * @param log
     * @return
     */
    Integer insert(BaseApiLog log);

    /**
     * 批量添加日志
     *
     * @param logs
     * @return
     */
    Integer batchInsert(List<BaseApiLog> logs);

    /**
     * 日志列表
     *
     * @param param
     * @return
     */
    List<BaseApiLog> selectList(ApiLogParam param);

    /**
     * 日志详情
     *
     * @param logId
     * @return
     */
    BaseApiLog selectOne(Long logId);

    /**
     * 查询日志摘要统计
     *
     * @param param 统计筛选条件
     * @return 总量、成功量、失败量和耗时摘要
     */
    ApiLogSummaryVO selectLogSummary(ApiLogParam param);

    /**
     * 查询每日调用趋势
     *
     * @param param 统计筛选条件
     * @return 按 request_time 日期聚合的趋势数据
     */
    List<ApiLogDailyTrendVO> selectDailyTrend(ApiLogParam param);

    /**
     * 查询请求状态分布
     *
     * @param param 统计筛选条件
     * @return success/fail 分组计数
     */
    List<ApiLogGroupCountVO> selectStatusStats(ApiLogParam param);

    /**
     * 查询接口调用量Top10列表
     *
     * @param param 统计筛选条件
     * @return 按接口聚合的调用量Top10
     */
    List<ApiLogGroupCountVO> selectTopApis(ApiLogParam param);

    /**
     * 查询成功接口耗时Top10列表
     *
     * @param param 统计筛选条件，服务层会固定 request_status=success
     * @return 按接口平均耗时聚合的成功调用Top10
     */
    List<ApiLogGroupCountVO> selectTopCostApis(ApiLogParam param);

    /**
     * 查询IP访问量Top10列表
     *
     * @param param 统计筛选条件
     * @return 按request_ip分组聚合的访问量Top10
     */
    List<ApiLogGroupCountVO> selectTopIps(ApiLogParam param);

    /**
     * 分批删除指定时间之前的过期日志。
     *
     * @param endTime 截止时间（yyyy-MM-dd HH:mm:ss），删除 request_time &lt;= endTime 的记录
     * @param limit   单批删除上限
     * @return 本批删除行数
     */
    Integer deleteBefore(@Param("endTime") String endTime, @Param("limit") int limit);
}
