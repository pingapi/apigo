package cn.crabc.core.datasource.constant;

import java.util.Arrays;
import java.util.List;

/**
 * 常量类
 *
 * @author yuqf
 */
public class BaseConstant {

    /**
     * SQL脚本
     */
    public final static String BASE_SQL = "base_sql";

    /**
     * 数据源ID
     */
    public final static String DATA_SOURCE_ID = "data_source_id";

    /**
     * 页码
     */
    public final static String PAGE_NUM = "pageNum";

    /**
     * 每页大小
     */
    public final static String PAGE_SIZE = "pageSize";

    /**
     * 分页设置
     */
    public final static String PAGE_SETUP = "pageSetup";

    /**
     * 数据源类型
     */
    public final static String DATA_SOURCE_TYPE = "data_source_type";

    /**
     * 分页统计
     */
    public final static Integer PAGE_COUNT = 2;

    /**
     * 只分页
     */
    public final static Integer PAGE_ONLY = 1;

    /**
     * redis
     */
    public final static String REDIS_CACHE = "redis";

    /**
     * API数据缓存
     */
    public static final String CACHE_API_DETAIL = "api_detail";

    public static final String CACHE_METADATA_CATALOG = "metadata_catalog:";
    /**
     * redis缓存schema
     */
    public static final String CACHE_METADATA_SCHEMA = "metadata_schema:";
    /**
     * redis缓存表
     */
    public static final String CACHE_METADATA_TABLE = "metadata_table:";
    /**
     * redis缓存字段
     */
    public static final String CACHE_METADATA_COLUMN = "metadata_column:";
    /**
     * SQL执行方式
     */
    public static final String BASE_API_EXEC_TYPE = "base_api_exec_type:";

    /**
     * 是否开启事务，接口在线调用和测试执行通过该参数控制多脚本提交/回滚边界
     */
    public static final String TRANSACTION_ENABLED = "transactionEnabled";

    /**
     * 脚本超时时间（秒），接口在线调用和测试执行通过该参数控制SQL最长执行时间
     */
    public static final String SCRIPT_TIMEOUT_SECONDS = "scriptTimeoutSeconds";

    /**
     * 未配置脚本超时时间时使用的平台默认值（秒）
     */
    public static final int DEFAULT_SCRIPT_TIMEOUT_SECONDS = 30;

    /**
     * 脚本超时时间下限（秒）
     */
    public static final int MIN_SCRIPT_TIMEOUT_SECONDS = 1;

    /**
     * 脚本超时时间上限（秒）
     */
    public static final int MAX_SCRIPT_TIMEOUT_SECONDS = 300;

    /**
     * 单次查询结果最大行数。
     * <p>
     * 分页路径用它作为分页大小上限，非分页路径由 {@code ScriptTimeoutInterceptor}
     * 通过 {@code Statement.setMaxRows} 兜底，避免分页判定失误时整表结果进内存。
     */
    public static final int MAX_RESULT_LIMIT = 10000;

    /**
     * 需要指定catalog的数据源
     */
    public static final List<String> CATALOG_DATA_SOURCE = Arrays.asList("sybase","mysql","mariadb","doris","starrocks","tidb","tdsql");

}
