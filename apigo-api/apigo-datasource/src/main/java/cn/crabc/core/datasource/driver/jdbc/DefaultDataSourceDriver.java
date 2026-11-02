package cn.crabc.core.datasource.driver.jdbc;

import cn.crabc.core.datasource.config.JdbcDataSourceRouter;
import cn.crabc.core.datasource.util.DataSourceLocks;
import cn.crabc.core.spi.DataSourceDriver;
import cn.crabc.core.spi.bean.BaseDataSource;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.util.JdbcConstants;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 默认通用方法实现类
 *
 * @author yuqf
 */
public abstract class DefaultDataSourceDriver implements DataSourceDriver {
    private static final Logger log = LoggerFactory.getLogger(DefaultDataSourceDriver.class);
    private static final String DUCKDB = "duckdb";

    /**
     * 动态连接池默认上限：2 会让"元数据浏览 + 并发查询"直接排队，按常见数据源规模取 10。
     */
    private static final int DEFAULT_MAX_POOL_SIZE = 10;
    private static final int MIN_POOL_SIZE = 1;
    private static final int MAX_POOL_SIZE = 50;
    private static final long DEFAULT_CONNECTION_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30L);
    private static final long MIN_CONNECTION_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(1L);
    private static final long MAX_CONNECTION_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(60L);

    @Override
    public String getName() {
        return "jdbc";
    }

    @Override
    public String test(BaseDataSource baseDataSource) {
        DataSource dataSource = null;
        try {
            dataSource = createDataSource(baseDataSource, true);
            // 只要拿到连接即视为连通；连接由 try-with-resources 关闭，避免 finally 里 close 抛错导致临时池泄漏
            try (Connection ignored = dataSource.getConnection()) {
                return "1";
            }
        } catch (Exception e) {
            Throwable cause = e.getCause();
            log.error("数据库测试异常：{}", e.getMessage());
            return cause == null ? e.getMessage() : cause.getLocalizedMessage();
        } finally {
            closeQuietly(dataSource);
        }
    }

    @Override
    public void init(BaseDataSource ds) {
        String datasourceId = ds.getDatasourceId().toString();
        // 同一数据源的"建池-预热-替换-关旧池"必须串行，否则并发刷新会互相覆盖导致整池泄漏
        ReentrantLock lock = DataSourceLocks.of(datasourceId);
        DataSource oldDataSource = null;
        DataSource newDataSource = null;
        boolean initSuccess = false;
        lock.lock();
        try {
            oldDataSource = JdbcDataSourceRouter.exist(datasourceId)
                    ? JdbcDataSourceRouter.getDataSource(datasourceId) : null;
            newDataSource = createDataSource(ds, false);

            // 连接池预热
            try (Connection conn = newDataSource.getConnection()) {
                if (conn.isValid(1)) {
                    log.debug("数据源初始化成功.");
                    initSuccess = true;
                } else {
                    log.error("数据源初始化失败.");
                }
            } catch (SQLException e) {
                log.error("Failed to warm up DataSource. Shutting down.", e);
            }

            // 仅在初始化成功时才替换路由池中的实例
            if (initSuccess) {
                JdbcDataSourceRouter.setDataSource(datasourceId, newDataSource);
                // 所有权已转移给路由池，本方法不再负责关闭
                newDataSource = null;
            }
        } finally {
            lock.unlock();
            // 初始化失败：关闭新建的池；已成功替换时 newDataSource 为 null，不会误关
            closeQuietly(newDataSource);
        }

        if (initSuccess) {
            // 关旧池放在锁外：close 可能阻塞，不能拖住其它刷新与查询
            closeQuietly(oldDataSource);
        }
    }

    @Override
    public void destroy(String dataSourceId) {
        JdbcDataSourceRouter.destroy(dataSourceId);
    }

    /**
     * DuckDB文件库不使用连接池，其他JDBC数据源沿用Hikari连接池配置。
     */
    private DataSource createDataSource(BaseDataSource ds, boolean isTest) {
        if (isDuckDb(ds)) {
            return new DuckDbDataSource(ds);
        }
        return createHikariDataSource(ds, isTest);
    }

    /**
     * 创建Hikari连接池
     * @param ds
     * @param isTest
     * @return
     */
    private HikariDataSource createHikariDataSource(BaseDataSource ds, boolean isTest) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setUsername(ds.getUsername());
        dataSource.setPassword(ds.getPassword());
        dataSource.setJdbcUrl(ds.getJdbcUrl());
        
        if (isTest) {
            dataSource.setMinimumIdle(0);
            dataSource.setInitializationFailTimeout(1);
            dataSource.setConnectionTimeout(2000);
        } else {
            // 参数统一钳制：用户配置越界时按安全区间落库，避免 Hikari 归一化成意外值
            int maxPoolSize = clamp(ds.getMaxActive() != null ? ds.getMaxActive() : DEFAULT_MAX_POOL_SIZE,
                    MIN_POOL_SIZE, MAX_POOL_SIZE);
            int minIdle = clamp(ds.getMinIdle() != null ? ds.getMinIdle() : 1, 0, maxPoolSize);
            dataSource.setMaximumPoolSize(maxPoolSize);
            dataSource.setMinimumIdle(minIdle);

            long maxLifetime = clampNonZero(ds.getMaxLifetime() != null ? ds.getMaxLifetime()
                    : TimeUnit.MINUTES.toMillis(15L), TimeUnit.SECONDS.toMillis(30L), TimeUnit.HOURS.toMillis(1L));
            dataSource.setMaxLifetime(maxLifetime);

            long idleTimeout = clampNonZero(ds.getIdleTimeout() != null ? ds.getIdleTimeout()
                    : TimeUnit.MINUTES.toMillis(10L), TimeUnit.SECONDS.toMillis(10L), TimeUnit.HOURS.toMillis(1L));
            dataSource.setIdleTimeout(idleTimeout);

            long connectTimeout = clamp(ds.getConnectTimeout() != null ? ds.getConnectTimeout()
                    : DEFAULT_CONNECTION_TIMEOUT_MS, MIN_CONNECTION_TIMEOUT_MS, MAX_CONNECTION_TIMEOUT_MS);
            dataSource.setConnectionTimeout(connectTimeout);

            if (ds.getKeepaliveTime() != null && ds.getKeepaliveTime() != 0) {
                dataSource.setKeepaliveTime(clampNonZero(ds.getKeepaliveTime(),
                        TimeUnit.SECONDS.toMillis(30L), TimeUnit.MINUTES.toMillis(30L)));
            }else{
                dataSource.setKeepaliveTime(TimeUnit.MINUTES.toMillis(2L));
            }
            dataSource.setLeakDetectionThreshold(TimeUnit.MINUTES.toMillis(2L));
            // 池名带上数据源ID，监控/线程转储时可直接定位是哪个数据源
            dataSource.setPoolName("apigo-ds-" + (ds.getDatasourceId() == null ? "unknown" : ds.getDatasourceId()));

            if ("oracle".equalsIgnoreCase(ds.getDatasourceType())) {
                // Oracle连接测试查询
                dataSource.setConnectionTestQuery("SELECT 1 FROM DUAL");
                // 设置连接初始化SQL，确保连接可用
                dataSource.setConnectionInitSql("SELECT 1 FROM DUAL");
            }
            
            // 自动提交设置
            dataSource.setAutoCommit(true);
        }
        
        setDriverClass(dataSource, ds.getDatasourceType());
        return dataSource;
    }

    /**
     * 加载特殊驱动
     */
    private void setDriverClass(HikariDataSource dataSource, String datasourceType) {
        if (datasourceType == null) {
            return;
        }
        switch (datasourceType.toLowerCase()) {
            case "dm":
                dataSource.setDriverClassName(JdbcConstants.DM_DRIVER);
                break;
            case "oceanbase":
                dataSource.setDriverClassName(JdbcConstants.OCEANBASE_DRIVER2);
                break;
            case "clickhouse":
                dataSource.setDriverClassName(JdbcConstants.CLICKHOUSE_DRIVER_NEW);
                break;
            case "duckdb":
                dataSource.setDriverClassName("org.duckdb.DuckDBDriver");
                break;
            case "dolphindb":
                dataSource.setDriverClassName("com.dolphindb.jdbc.Driver");
                break;
        }
    }

    /**
     * 数据源类型统一在驱动层判断，避免调用方感知DuckDB的特殊初始化路径。
     */
    private boolean isDuckDb(BaseDataSource ds) {
        return ds != null && DUCKDB.equalsIgnoreCase(ds.getDatasourceType());
    }

    /**
     * 只关闭真正持有连接池资源的数据源，DuckDB直连数据源无常驻连接可关闭。
     */
    private void closeDataSource(DataSource dataSource) {
        if (dataSource instanceof DruidDataSource druidDataSource) {
            druidDataSource.close();
        } else if (dataSource instanceof HikariDataSource hikariDataSource) {
            hikariDataSource.close();
        }
    }

    /**
     * 关闭数据源并吞掉异常：清理路径不能因为 close 失败而漏掉后续释放。
     */
    private void closeQuietly(DataSource dataSource) {
        if (dataSource == null) {
            return;
        }
        try {
            closeDataSource(dataSource);
        } catch (Exception e) {
            log.warn("关闭数据源失败：{}", e.getMessage(), e);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    private static long clamp(long value, long min, long max) {
        return Math.min(Math.max(value, min), max);
    }

    /**
     * 0 在 Hikari 中表示"禁用"，保留原语义；非 0 才钳制到合法区间。
     */
    private static long clampNonZero(long value, long min, long max) {
        if (value == 0) {
            return 0;
        }
        return clamp(value, min, max);
    }
}
