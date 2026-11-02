package cn.crabc.core.app.entity.param;

import lombok.Getter;
import lombok.Setter;

/**
 * API日志查询请求参数
 *
 * @author yuqf
 */
@Setter
@Getter
public class ApiLogParam {
    /**
     * 请求结果，取值沿用日志写入的 success/fail。
     */
    private String result;

    /**
     * 接口名称或接口路径关键字。
     */
    private String keyword;

    /**
     * 应用名称或调用凭证名称。
     */
    private String appName;

    /**
     * 查询开始时间，格式为 yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd。
     */
    private String startTime;

    /**
     * 查询结束时间，格式为 yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd。
     */
    private String endTime;

    /**
     * 分页页码，仅日志列表使用。
     */
    private Integer pageNum;

    /**
     * 分页大小，仅日志列表使用。
     */
    private Integer pageSize;

    /**
     * 归属过滤：由服务端按当前登录身份写入，不接受前端传入。
     * <p>
     * null 表示不限（管理员），非空时只统计该用户创建的接口日志。
     */
    private String createBy;
}
