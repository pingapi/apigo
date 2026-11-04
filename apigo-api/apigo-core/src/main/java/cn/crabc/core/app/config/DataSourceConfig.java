package cn.crabc.core.app.config;

import cn.crabc.core.datasource.config.JdbcDataSourceRouter;
import cn.crabc.core.datasource.driver.DataSourceManager;
import cn.crabc.core.datasource.driver.jdbc.JdbcDataSourceDriver;
import cn.crabc.core.datasource.mapper.BaseDataHandleMapper;
import cn.crabc.core.spi.DataSourceDriver;
import com.alibaba.druid.pool.DruidDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.HashMap;

/**
 * 数据源路由配置
 *
 * @author yuqf
 */
@EnableCaching
@EnableScheduling
@Configuration
public class DataSourceConfig {

    @Value("${spring.datasource.url}")
    private String jdbcUrl;
    @Value("${spring.datasource.username}")
    private String username;
    @Value("${spring.datasource.password}")
    private String password;

    /**
     * 连接泄漏回收开关：默认关闭。
     * <p>
     * Druid 会在启动时对 {@code removeAbandoned=true} 恒定告警，且占用超过阈值的连接会被强制回收，
     * 长导出与新增的日志保留任务这类 >300s 的操作会被掐断。排查泄漏时再显式打开。
     */
    @Value("${spring.datasource.druid.remove-abandoned:false}")
    private boolean removeAbandoned;

    /**
     * 连接判定为泄漏的占用时长（秒），仅在 {@code remove-abandoned=true} 时生效。
     */
    @Value("${spring.datasource.druid.remove-abandoned-timeout-seconds:300}")
    private int removeAbandonedTimeoutSeconds;

    /**
     * 默认系统数据源
     *
     * @return
     */
    @Bean
    public JdbcDataSourceRouter defaultDataSource() {
        DruidDataSource dataSource = new DruidDataSource();
        dataSource.setUrl(jdbcUrl);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setInitialSize(1);
        dataSource.setMinIdle(1);
        dataSource.setMaxActive(20);
        // 配置获取连接等待超时的时间
        dataSource.setMaxWait(6000);
        dataSource.setKeepAlive(true);
        //  配置一个连接在池中最小生存的时间，单位是毫秒
        dataSource.setMinEvictableIdleTimeMillis(300000);
        dataSource.setMaxEvictableIdleTimeMillis(900000);
        // 配置间隔多久才进行一次检测，检测需要关闭的空闲连接，单位是毫秒
        dataSource.setTimeBetweenEvictionRunsMillis(60000);
        dataSource.setTestWhileIdle(true);
        dataSource.setTestOnBorrow(false);
        dataSource.setTestOnReturn(false);
        // 连接泄漏检测：默认关闭，打开后才设置回收阈值并打印泄漏栈
        dataSource.setRemoveAbandoned(removeAbandoned);
        if (removeAbandoned) {
            dataSource.setRemoveAbandonedTimeout(Math.max(1, removeAbandonedTimeoutSeconds));
            dataSource.setLogAbandoned(true);
        }
        JdbcDataSourceRouter dynamic = new JdbcDataSourceRouter();
        dynamic.setTargetDataSources(new HashMap<>());
        // 设置默认数据源
        dynamic.setDefaultTargetDataSource(dataSource);
        return dynamic;
    }

    /**
     * JDBC 数据源驱动
     *
     * @param baseDataHandleMapper
     * @return
     */
    @Bean
    public DataSourceDriver dataSourceDriver(BaseDataHandleMapper baseDataHandleMapper) {
        return new JdbcDataSourceDriver(baseDataHandleMapper);
    }

    /**
     * 数据源插件驱动
     *
     * @param jdbcDataSourceDriver
     * @return
     */
    @Bean
    public DataSourceManager dataSourceDriverManager(DataSourceDriver jdbcDataSourceDriver) {
        return new DataSourceManager(jdbcDataSourceDriver);
    }
}
