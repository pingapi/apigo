package cn.crabc.core.datasource.config;

import cn.crabc.core.datasource.constant.BaseConstant;
import cn.crabc.core.datasource.driver.DataSourceManager;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import com.alibaba.druid.pool.DruidDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * JDBC数据源 动态路由
 *
 * @author yuqf
 */
public class JdbcDataSourceRouter extends AbstractRoutingDataSource {

    private static final Logger log = LoggerFactory.getLogger(JdbcDataSourceRouter.class);
    /**
     * 当前线程数据源KEY
     */
    private static final ThreadLocal<String> DATA_SOURCE_KEY = new ThreadLocal<>();

    /**
     * 获取数据源key
     */
    public static String getDataSourceKey() {
        return DATA_SOURCE_KEY.get();
    }

    /**
     * 设置数据源key
     */
    public static void setDataSourceKey(String key) {
        DATA_SOURCE_KEY.set(key);
    }

    /**
     * 移除数据源
     */
    public static void remove() {
        DATA_SOURCE_KEY.remove();
    }

    /**
     * 判断数据源是否存在
     */
    public static boolean exist(String dataSourceId) {
        return DataSourceManager.DATA_SOURCE_POOL_JDBC.get(getDataSourceId(dataSourceId)) != null;
    }

    /**
     * 获取数据源ID
     * @param dataSourceId
     * @return
     */
    private static String getDataSourceId(String dataSourceId) {
        return dataSourceId == null ? null : dataSourceId.split(":")[0];
    }

    /**
     * 销毁数据源
     */
    public static void destroy(String dataSourceId) {
        DataSource dataSource = DataSourceManager.DATA_SOURCE_POOL_JDBC.get(getDataSourceId(dataSourceId));
        if (dataSource == null) {
            return;
        }
        // 移除缓存
        DataSourceManager.DATA_SOURCE_POOL_JDBC.remove(dataSourceId);
        // 关闭连接池
        if (dataSource instanceof DruidDataSource druidDataSource) {
            druidDataSource.close();
        } else if (dataSource instanceof HikariDataSource hikariDataSource) {
            hikariDataSource.close();
        }
    }

    /**
     * 获取指定数据源
     */
    public static DataSource getDataSource(String dataSourceId) {
        DataSource dataSource = DataSourceManager.DATA_SOURCE_POOL_JDBC.get(getDataSourceId(dataSourceId));
        if (dataSource == null) {
            throw new CustomException(ErrorStatusEnum.DATASOURCE_NOT_FOUNT.getCode(), 
                ErrorStatusEnum.DATASOURCE_NOT_FOUNT.getMassage());
        }
        return dataSource;
    }

    /**
     * 获取当前数据源
     */
    public static DataSource getDataSource() {
        String dataSourceKey = getDataSourceKey();
        DataSource dataSource = DataSourceManager.DATA_SOURCE_POOL_JDBC.get(getDataSourceId(dataSourceKey));
        if (dataSource == null) {
            throw new CustomException(ErrorStatusEnum.DATASOURCE_NOT_FOUNT.getCode(), ErrorStatusEnum.DATASOURCE_NOT_FOUNT.getMassage());
        }

        return dataSource;
    }

    /**
     * 添加数据源
     *
     * @param dataSourceId
     **/
    public static void setDataSource(String dataSourceId, DataSource dataSource) {
        DataSourceManager.DATA_SOURCE_POOL_JDBC.put(dataSourceId, dataSource);
    }

    /**
     * 切换数据源
     */
    @Override
    protected DataSource determineTargetDataSource() {
        Object dataSourceKey = determineCurrentLookupKey();
        if (dataSourceKey == null) {
            return super.getResolvedDefaultDataSource();
        }
        String dataSourceId = getDataSourceId(dataSourceKey.toString());
        DataSource dataSource = DataSourceManager.DATA_SOURCE_POOL_JDBC.get(dataSourceId);
        if (dataSource == null) {
            throw new CustomException(ErrorStatusEnum.DATASOURCE_NOT_FOUNT.getCode(),
                ErrorStatusEnum.DATASOURCE_NOT_FOUNT.getMassage());
        }
        return dataSource;
    }

    /**
     * 获取连接
     *
     * @return
     * @throws SQLException
     */
    @Override
    public Connection getConnection() throws SQLException {
        log.debug("---->>切换数据库连接----");
        return applySchemaSwitch(this.determineTargetDataSource().getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return applySchemaSwitch(this.determineTargetDataSource().getConnection(username, password));
    }

    /**
     * 按路由 key（dataSourceId:dataSourceType:schema）切换连接的库/Schema。
     *
     * 切换必须在 SQL 执行期间保持有效，因此不在本方法内还原，而是返回代理连接，
     * 在调用方 close（归还连接池）之前还原原始库/Schema，避免脏状态污染后续请求。
     */
    private Connection applySchemaSwitch(Connection connection) throws SQLException {
        Object dataSourceKey = this.determineCurrentLookupKey();
        if (dataSourceKey == null) {
            return connection;
        }
        String[] dataSourceStr = dataSourceKey.toString().split(":");
        // key 缺少 schema 段时不切换，保持数据源默认库/Schema
        if (dataSourceStr.length < 3) {
            return connection;
        }
        String dataSourceType = dataSourceStr[1];
        String targetSchema = dataSourceStr[2];
        // 库/Schema 语义区分：MySQL 系用 catalog，其余用 schema
        boolean catalogMode = BaseConstant.CATALOG_DATA_SOURCE.contains(dataSourceType);

        try {
            String original = catalogMode ? connection.getCatalog() : connection.getSchema();
            if (!equalsIgnoreNull(original, targetSchema)) {
                if (catalogMode) {
                    connection.setCatalog(targetSchema);
                } else {
                    connection.setSchema(targetSchema);
                }
                log.debug("连接切换完成: {} -> {}", original, targetSchema);
                // 交给调用方的连接在 close 时还原，保证本条SQL执行期间一直是目标库/Schema
                return (Connection) Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[]{Connection.class},
                        new SchemaRestoreHandler(connection, catalogMode, original));
            }
            return connection;
        } catch (SQLException | RuntimeException e) {
            closeQuietly(connection);
            log.error("数据源连接切换库/Schema失败, dataSourceKey: {}", dataSourceKey, e);
            throw new SQLException(e);
        }
    }

    /**
     * 关闭连接，忽略关闭异常
     */
    private void closeQuietly(Connection connection) {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException ignored) {
            // 关闭失败不影响主流程
        }
    }

    /**
     * 连接代理：只拦截 close，在归还连接池之前还原原始库/Schema
     */
    private static final class SchemaRestoreHandler implements InvocationHandler {

        private final Connection target;
        /**
         * true：切换的是 catalog（MySQL 系）；false：切换的是 schema
         */
        private final boolean catalogMode;
        /**
         * 连接池中的原始库/Schema，连接归还前需要恢复
         */
        private final String original;

        private SchemaRestoreHandler(Connection target, boolean catalogMode, String original) {
            this.target = target;
            this.catalogMode = catalogMode;
            this.original = original;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();
            if ("close".equals(methodName)) {
                restore();
                target.close();
                return null;
            }
            // equals/hashCode 透传到真实连接，避免代理与连接池连接比较时语义错乱
            if ("equals".equals(methodName) && args != null && args.length == 1) {
                return proxy == args[0] || target.equals(args[0]);
            }
            if ("hashCode".equals(methodName)) {
                return target.hashCode();
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }

        /**
         * 还原连接状态，失败只记录日志：连接随后会被关闭并归还连接池
         */
        private void restore() {
            try {
                if (target.isClosed()) {
                    return;
                }
                if (catalogMode) {
                    target.setCatalog(original);
                } else {
                    target.setSchema(original);
                }
            } catch (Exception e) {
                log.warn("还原连接库/Schema失败, 原始值: {}", original, e);
            }
        }
    }

    /**
     * 对比方法
     *
     */
    private boolean equalsIgnoreNull(String original, String target) {
        if (original == null && target == null) {
            return true;
        }
        if (original == null || target == null) {
            return false;
        }
        return original.equals(target);
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return getDataSourceKey();
    }
}
