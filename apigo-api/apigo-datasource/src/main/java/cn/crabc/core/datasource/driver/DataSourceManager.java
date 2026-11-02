package cn.crabc.core.datasource.driver;

import cn.crabc.core.datasource.exception.CustomException;
import cn.crabc.core.datasource.util.DataSourceLocks;
import cn.crabc.core.spi.DataSourceDriver;
import cn.crabc.core.spi.MetaDataMapper;
import cn.crabc.core.spi.StatementMapper;
import cn.crabc.core.spi.bean.BaseDataSource;
import com.alibaba.druid.pool.DruidDataSource;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据源驱动管理
 *
 * @author yuqf
 */
public class DataSourceManager {

    /**
     * JDBC数据源连接池
     */
    public static final Map<String, DataSource> DATA_SOURCE_POOL_JDBC = new ConcurrentHashMap<>();

    /**
     * 默认数据源驱动实现
     */
    private DataSourceDriver defaultDriver;

    public DataSourceManager(DataSourceDriver dataSourceDriver) {
        // 默认JDBC驱动
        this.defaultDriver = dataSourceDriver;
    }

    /**
     * 创建数据源
     *
     * @param dataSource
     */
    public void createDataSource(BaseDataSource dataSource) {
        this.defaultDriver.init(dataSource);
    }

    /**
     * 测试数据源
     *
     * @param dataSource
     * @return
     */
    public String test(BaseDataSource dataSource) {
        return defaultDriver.test(dataSource);
    }


    /**
     * 获取数据源驱动
     *
     * @param datasourceId
     * @return
     */
    public DataSourceDriver getDataSource(String datasourceId) {
        DataSourceDriver dataSourceDriver = null;
        DataSource dataSource = DATA_SOURCE_POOL_JDBC.get(datasourceId);
        if (dataSource != null) {
            dataSourceDriver = this.defaultDriver;
        }
        if (dataSourceDriver == null) {
            throw new CustomException(51001, "数据源不存在！");
        }
        return dataSourceDriver;
    }

    /**
     * 元数据对象
     * @param datasourceId
     * @return
     */
    public MetaDataMapper getMetaData(String datasourceId){
        DataSourceDriver dataSource = this.getDataSource(datasourceId);
        return dataSource.getMetaData();
    }

    /**
     * 数据处理对象
     * @param datasourceId
     * @return
     */
    public StatementMapper getStatementMapper(String datasourceId){
        DataSourceDriver dataSource = this.getDataSource(datasourceId);
        return dataSource.getStatement();
    }
    
    /**
     * 删除数据源驱动
     * <p>
     * 与建池共用数据源级锁，避免"刷新线程正在替换池"时删池出现覆盖/漏关；
     * 兼容传入 {@code id:type:schema} 的路由 key，统一按数据源ID处理。
     *
     * @param datasourceId
     */
    public void remove(String datasourceId) {
        if (datasourceId == null) {
            return;
        }
        String id = datasourceId.split(":")[0];
        DataSource dataSource;
        java.util.concurrent.locks.ReentrantLock lock = DataSourceLocks.of(id);
        lock.lock();
        try {
            dataSource = DATA_SOURCE_POOL_JDBC.remove(id);
        } finally {
            lock.unlock();
        }
        // 关闭动作放到锁外，避免 close 阻塞后续刷新
        closeQuietly(dataSource);
    }

    /**
     * 关闭数据源并吞掉异常，保证清理流程不中断。
     */
    private void closeQuietly(DataSource dataSource) {
        if (dataSource == null) {
            return;
        }
        try {
            if (dataSource instanceof DruidDataSource druidDataSource) {
                druidDataSource.close();
            } else if (dataSource instanceof HikariDataSource hikariDataSource) {
                hikariDataSource.close();
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(DataSourceManager.class)
                    .warn("关闭数据源失败：{}", e.getMessage(), e);
        }
    }
}
