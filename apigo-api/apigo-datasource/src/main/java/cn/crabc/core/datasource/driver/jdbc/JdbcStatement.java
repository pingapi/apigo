/*
 * Copyright 2023, crabc.cn (creabc@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.crabc.core.datasource.driver.jdbc;

import cn.crabc.core.datasource.config.JdbcDataSourceRouter;
import cn.crabc.core.datasource.constant.BaseConstant;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import cn.crabc.core.datasource.mapper.BaseDataHandleMapper;
import cn.crabc.core.datasource.util.PageInfo;
import cn.crabc.core.datasource.util.SqlExceptionUtils;
import cn.crabc.core.spi.StatementMapper;
import com.github.pagehelper.PageHelper;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.mapping.SqlSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;
import java.util.regex.Pattern;

/**
 * JDBC SQL执行器，承接单条查询/变更和多脚本DML执行。
 */
public class JdbcStatement implements StatementMapper<Map<String, Object>> {
    private static final Logger log = LoggerFactory.getLogger(JdbcStatement.class);
    private static final String[] SCRIPT_TAGS = {"</if>","</foreach>","</where>","</set>","</choose>","</when>","</trim>","</otherwise"};
    private final BaseDataHandleMapper baseMapper;
    private static final int PAGE_SIZE = 1000;
    private static final int PAGE_NUM = 1;
    private final Configuration myBatisConfiguration = new Configuration();

    // 安全配置常量
    private static final int MAX_PARAM_LENGTH = 10000; // 单个参数最大长度
    private static final int MAX_RESULT_LIMIT = 10000; // 查询结果最大行数
    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = BaseConstant.DEFAULT_SCRIPT_TIMEOUT_SECONDS; // 未配置时的默认超时时间（秒）
    private static final int MAX_BATCH_SIZE = 1000; // 批量操作最大数量

    public JdbcStatement(BaseDataHandleMapper baseMapper) {
        this.baseMapper = baseMapper;
    }

    @Override
    public Map<String, Object> selectOne(String dataSourceId, String schema, String sql, Object params) {
        List<Map<String, Object>> maps = selectList(dataSourceId, schema, sql, params);
        return maps.isEmpty() ? new HashMap<>() : maps.get(0);
    }

    @Override
    public List<Map<String, Object>> selectList(String dataSourceId, String schema, String sql, Object params) {
        PageInfo page = selectPage(dataSourceId, schema, sql, params, PAGE_NUM, PAGE_SIZE);
        return page.getList();
    }

    @Override
    public PageInfo selectPage(String dataSourceId, String schema, String sql, Object params, int pageNum, int pageSize) {
        String execType = null;
        List<LinkedHashMap<String, Object>> list = new ArrayList<>();
        int timeoutSeconds = DEFAULT_QUERY_TIMEOUT_SECONDS;
        try {
            // 1. 参数安全验证和预处理
            Map<String, Object> paramsMap = setParams(dataSourceId, schema, sql, params);
            timeoutSeconds = resolveTimeoutSeconds(paramsMap);

            validateSqlSafety(sql);
            execType = (String)paramsMap.get(BaseConstant.BASE_API_EXEC_TYPE);

            Object pageSetup = paramsMap.get(BaseConstant.PAGE_SETUP);
            int pageCount = pageSetup != null ? Integer.parseInt(pageSetup.toString()) : 0;

            // 2. 强制限制分页大小，防止大结果集导致内存溢出
            pageSize = Math.min(pageSize, MAX_RESULT_LIMIT);

            // 3. 分页查询配置
            // 页面预览结果不需要查询总数
            if ("preview".equals(execType)) {
                PageHelper.startPage(pageNum, pageSize, false);
            }else if (pageCount == 0 && !checkPage(sql)) {
                PageHelper.startPage(pageNum, pageSize, false);
            }else if (pageCount != 0 && !checkPage(sql)){
                PageHelper.startPage(pageNum, pageSize, true);
            }

            // 4. 执行查询
            list = baseMapper.executeQuery(paramsMap);

        } catch (Exception e) {
            Throwable cause = e.getCause();
            String errorMsg = cause == null ? e.getMessage() : cause.getMessage();
            // 5. 安全的错误处理：记录完整错误但只返回安全信息
            log.error("SQL执行失败 - 数据源: {}, Schema: {}, SQL前缀: {}, 错误: {}",
                     dataSourceId, schema, truncateSql(sql), errorMsg, e);

            boolean timeout = SqlExceptionUtils.isTimeout(e);
            if (execType == null) {
                if (timeout) {
                    throw new CustomException(ErrorStatusEnum.API_SCRIPT_TIMEOUT.getCode(), timeoutMessage(timeoutSeconds));
                }
                // 返回通用错误信息，不暴露SQL细节
                throw new CustomException(51000, "查询执行失败，请检查参数和权限");
            } else {
                LinkedHashMap<String, Object> errorMap = new LinkedHashMap<>();
                errorMap.put("执行异常", timeout ? timeoutMessage(timeoutSeconds) : "查询执行失败, 错误信息: " + errorMsg);
                list.add(errorMap);
            }
        } finally {
            // 6. 确保资源释放
            PageHelper.clearPage();
            // 清理ThreadLocal，释放数据源key引用
            JdbcDataSourceRouter.remove();
        }
        return new PageInfo<>(list, pageNum, pageSize);
    }

    @Override
    public int insert(String dataSourceId, String schema, String sql, Object params) {
        int timeoutSeconds = DEFAULT_QUERY_TIMEOUT_SECONDS;
        try {
            // 1. SQL安全验证
            validateSqlSafety(sql);
            // 2. 参数处理和执行
            Map<String, Object> paramsMap = setParams(dataSourceId, schema, sql, params);
            timeoutSeconds = resolveTimeoutSeconds(paramsMap);
            return baseMapper.executeInsert(paramsMap);
        } catch (Exception e) {
            // 3. 安全的错误处理
            log.error("SQL插入失败 - 数据源: {}, Schema: {}, 错误: {}", dataSourceId, schema, e.getMessage());
            if (SqlExceptionUtils.isTimeout(e)) {
                throw new CustomException(ErrorStatusEnum.API_SCRIPT_TIMEOUT.getCode(), timeoutMessage(timeoutSeconds));
            }
            throw new CustomException(ErrorStatusEnum.API_SQL_ERROR.getCode(), "插入操作失败：" + e.getMessage());
        } finally {
            JdbcDataSourceRouter.remove();
        }
    }

    @Override
    public int delete(String dataSourceId, String schema, String sql, Object params) {
        int timeoutSeconds = DEFAULT_QUERY_TIMEOUT_SECONDS;
        try {
            // 1. SQL安全验证
            validateSqlSafety(sql);
            // 2. 参数处理和执行
            Map<String, Object> paramsMap = setParams(dataSourceId, schema, sql, params);
            timeoutSeconds = resolveTimeoutSeconds(paramsMap);
            return baseMapper.executeDelete(paramsMap);
        } catch (Exception e) {
            // 3. 安全的错误处理
            log.error("SQL删除失败 - 数据源: {}, Schema: {}, 错误: {}", dataSourceId, schema, e.getMessage());
            if (SqlExceptionUtils.isTimeout(e)) {
                throw new CustomException(ErrorStatusEnum.API_SCRIPT_TIMEOUT.getCode(), timeoutMessage(timeoutSeconds));
            }
            throw new CustomException(ErrorStatusEnum.API_SQL_ERROR.getCode(), "删除操作失败");
        } finally {
            JdbcDataSourceRouter.remove();
        }
    }

    @Override
    public int update(String dataSourceId, String schema, String sql, Object params) {
        int timeoutSeconds = DEFAULT_QUERY_TIMEOUT_SECONDS;
        try {
            // 1. SQL安全验证
            validateSqlSafety(sql);
            // 2. 参数处理和执行
            Map<String, Object> paramsMap = setParams(dataSourceId, schema, sql, params);
            timeoutSeconds = resolveTimeoutSeconds(paramsMap);
            return baseMapper.executeUpdate(paramsMap);
        } catch (Exception e) {
            // 3. 安全的错误处理
            log.error("SQL更新失败 - 数据源: {}, Schema: {}, 错误: {}", dataSourceId, schema, e.getMessage());
            if (SqlExceptionUtils.isTimeout(e)) {
                throw new CustomException(ErrorStatusEnum.API_SCRIPT_TIMEOUT.getCode(), timeoutMessage(timeoutSeconds));
            }
            throw new CustomException(ErrorStatusEnum.API_SQL_ERROR.getCode(), "更新操作失败" + e.getMessage());
        } finally {
            JdbcDataSourceRouter.remove();
        }
    }

    /**
     * 多脚本DML使用同一连接顺序执行，事务开关决定失败时是否回滚已执行脚本。
     */
    @Override
    public int executeBatchDml(String dataSourceId, String schema, List<String> sqlList, Object params, boolean transactionEnabled) {
        // 1. 批量SQL数量限制，防止过大批量导致长时间锁定连接
        if (sqlList == null || sqlList.isEmpty()) {
            return 0;
        }
        if (sqlList.size() > MAX_BATCH_SIZE) {
            throw new CustomException(ErrorStatusEnum.API_SQL_ERROR.getCode(),
                "批量SQL数量超过限制，最大支持 " + MAX_BATCH_SIZE + " 条");
        }

        Map<String, Object> paramsMap = copyParams(params);
        int timeoutSeconds = resolveTimeoutSeconds(paramsMap);
        String dataSourceKey = buildDataSourceKey(dataSourceId, schema, paramsMap);
        Connection connection = null;
        boolean oldAutoCommit = true;
        boolean autoCommitChanged = false;
        Integer oldNetworkTimeout = null;
        try {
            // 2. 获取数据库连接
            connection = getConnection(dataSourceKey, schema, paramsMap);
            oldAutoCommit = connection.getAutoCommit();

            // 3. 设置连接超时，防止长时间占用连接
            oldNetworkTimeout = applyNetworkTimeout(connection, timeoutSeconds);

            // 4. 事务控制
            if (transactionEnabled && oldAutoCommit) {
                connection.setAutoCommit(false);
                autoCommitChanged = true;
            }

            int affectedRows = 0;
            long startTime = System.currentTimeMillis();
            // 5. 逐条执行SQL并累计影响行数
            for (String sql : sqlList) {
                validateSqlSafety(sql); // 每条SQL都要进行安全检查
                affectedRows += executeDml(connection, sql, paramsMap, timeoutSeconds);
                // 6. 多脚本共用同一超时预算，超过配置时间立即终止后续脚本
                if (System.currentTimeMillis() - startTime > timeoutSeconds * 1000L) {
                    throw new CustomException(ErrorStatusEnum.API_SCRIPT_TIMEOUT.getCode(), timeoutMessage(timeoutSeconds));
                }
            }

            // 7. 提交事务
            if (transactionEnabled) {
                connection.commit();
            }
            return affectedRows;
        } catch (Exception e) {
            // 8. 事务回滚
            rollbackQuietly(connection, transactionEnabled);
            log.error("多脚本SQL执行失败 - 数据源: {}, 事务模式: {}, 错误: {}",
                     dataSourceId, transactionEnabled, e.getMessage());
            // 执行层已经归类过的业务异常直接抛出，避免被统一改写成SQL错误
            if (e instanceof CustomException customException) {
                throw customException;
            }
            if (SqlExceptionUtils.isTimeout(e)) {
                throw new CustomException(ErrorStatusEnum.API_SCRIPT_TIMEOUT.getCode(), timeoutMessage(timeoutSeconds));
            }
            throw new CustomException(ErrorStatusEnum.API_SQL_ERROR.getCode(), "批量操作执行失败");
        } finally {
            // 9. 恢复连接状态并释放资源
            restoreAutoCommit(connection, autoCommitChanged, oldAutoCommit);
            restoreNetworkTimeout(connection, oldNetworkTimeout);
            closeQuietly(connection);
            JdbcDataSourceRouter.remove();
        }
    }

    /**
     * 单条SQL仍沿用Mapper动态数据源路由，保持原有查询/变更执行路径不变。
     */
    private Map<String, Object> setParams(String dataSourceId, String schema, String sql, Object params) {
        Map<String, Object> paramsMap = copyParams(params);
        paramsMap.put(BaseConstant.BASE_SQL, sql);
        dataSourceId = buildDataSourceKey(dataSourceId, schema, paramsMap);
        JdbcDataSourceRouter.setDataSourceKey(dataSourceId);
        return paramsMap;
    }

    /**
     * 批量执行会追加内部控制参数，复制Map避免污染调用方原始参数。
     */
    private Map<String, Object> copyParams(Object params) {
        Map<String, Object> paramsMap = new HashMap<>();
        if (params instanceof Map) {
            paramsMap.putAll((Map<String, Object>) params);
        }
        return paramsMap;
    }

    /**
     * 接口配置了超时时间时以其为准，未配置则沿用平台默认值。
     */
    private int resolveTimeoutSeconds(Map<String, Object> paramsMap) {
        Object value = paramsMap == null ? null : paramsMap.get(BaseConstant.SCRIPT_TIMEOUT_SECONDS);
        if (value == null) {
            return DEFAULT_QUERY_TIMEOUT_SECONDS;
        }
        try {
            int timeoutSeconds = Integer.parseInt(value.toString());
            if (timeoutSeconds < BaseConstant.MIN_SCRIPT_TIMEOUT_SECONDS) {
                return DEFAULT_QUERY_TIMEOUT_SECONDS;
            }
            return Math.min(timeoutSeconds, BaseConstant.MAX_SCRIPT_TIMEOUT_SECONDS);
        } catch (NumberFormatException e) {
            return DEFAULT_QUERY_TIMEOUT_SECONDS;
        }
    }

    private String timeoutMessage(int timeoutSeconds) {
        return "脚本执行超时，接口配置的超时时间为 " + timeoutSeconds + " 秒";
    }

    /**
     * 网络超时是连接级设置，返回原值供归还连接前恢复。
     */
    private Integer applyNetworkTimeout(Connection connection, int timeoutSeconds) {
        try {
            Integer oldNetworkTimeout = connection.getNetworkTimeout();
            connection.setNetworkTimeout(null, timeoutSeconds * 1000);
            return oldNetworkTimeout;
        } catch (SQLException | RuntimeException e) {
            log.debug("设置连接网络超时失败，驱动可能不支持该能力", e);
            return null;
        }
    }

    /**
     * 连接来自池，归还前恢复网络超时，避免影响后续复用连接。
     */
    private void restoreNetworkTimeout(Connection connection, Integer oldNetworkTimeout) {
        if (connection == null || oldNetworkTimeout == null) {
            return;
        }
        try {
            connection.setNetworkTimeout(null, oldNetworkTimeout);
        } catch (SQLException | RuntimeException e) {
            log.debug("恢复连接网络超时失败", e);
        }
    }

    /**
     * schema路由key保持和原单条SQL一致，便于JDBC连接按库/Schema执行。
     */
    private String buildDataSourceKey(String dataSourceId, String schema, Map<String, Object> paramsMap) {
        if (schema != null && !schema.isEmpty()) {
            String dataSourceType = (String)paramsMap.getOrDefault(BaseConstant.DATA_SOURCE_TYPE, "");
            return String.format("%s:%s:%s", dataSourceId, dataSourceType, schema);
        }
        return dataSourceId;
    }

    /**
     * 批量DML直接取同一个物理连接，事务边界才能覆盖全部脚本。
     */
    private Connection getConnection(String dataSourceKey, String schema, Map<String, Object> paramsMap) throws SQLException {
        Connection connection = JdbcDataSourceRouter.getDataSource(dataSourceKey).getConnection();
        if (schema != null && !schema.isEmpty()) {
            String dataSourceType = (String) paramsMap.getOrDefault(BaseConstant.DATA_SOURCE_TYPE, "");
            if (BaseConstant.CATALOG_DATA_SOURCE.contains(dataSourceType)) {
                connection.setCatalog(schema);
            } else {
                connection.setSchema(schema);
            }
        }
        return connection;
    }

    /**
     * 每条脚本先交给MyBatis生成BoundSql，再用PreparedStatement绑定参数执行。
     */
    private int executeDml(Connection connection, String sql, Map<String, Object> paramsMap, int timeoutSeconds) throws SQLException {
        BoundSql boundSql = buildBoundSql(sql, paramsMap);
        try (PreparedStatement statement = connection.prepareStatement(boundSql.getSql())) {
            // 1. 设置SQL执行超时时间，防止慢查询
            try {
                statement.setQueryTimeout(timeoutSeconds);
            } catch (SQLException | RuntimeException e) {
                // 个别驱动不支持语句超时，保持原有执行行为
                log.debug("设置SQL语句超时失败，超时时间：{}秒", timeoutSeconds, e);
            }
            // 2. 绑定参数
            bindParameters(statement, boundSql, paramsMap);
            // 3. 执行更新
            return statement.executeUpdate();
        }
    }

    /**
     * 动态SQL片段只有出现MyBatis标签时才包script，普通SQL保持原样解析。
     */
    private BoundSql buildBoundSql(String sql, Map<String, Object> paramsMap) {
        LanguageDriver languageDriver = myBatisConfiguration.getDefaultScriptingLanguageInstance();
        SqlSource sqlSource = languageDriver.createSqlSource(myBatisConfiguration, addScript(sql), Map.class);
        return sqlSource.getBoundSql(paramsMap);
    }

    /**
     * MyBatis LanguageDriver需要script根标签才能解析foreach/if等动态标签。
     */
    private String addScript(String sql) {
        for (String tag : SCRIPT_TAGS) {
            if (sql.contains(tag)) {
                return "<script> " + sql + " </script>";
            }
        }
        return sql;
    }

    /**
     * 按BoundSql映射顺序绑定参数，避免多脚本执行时手写拼接参数。
     */
    private void bindParameters(PreparedStatement statement, BoundSql boundSql, Map<String, Object> paramsMap) throws SQLException {
        List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        for (int i = 0; i < parameterMappings.size(); i++) {
            String property = parameterMappings.get(i).getProperty();
            Object value = getParameterValue(boundSql, paramsMap, property);

            // 1. 参数安全验证
            if (value == null) {
                statement.setNull(i + 1, Types.NULL);
            } else {
                // 2. 字符串类型参数长度限制，防止超大参数攻击
                if (value instanceof String) {
                    String strValue = (String) value;
                    if (strValue.length() > MAX_PARAM_LENGTH) {
                        throw new IllegalArgumentException(
                            String.format("参数[%s]长度超过限制: %d > %d", property, strValue.length(), MAX_PARAM_LENGTH));
                    }
                }
                // 3. 绑定参数值
                statement.setObject(i + 1, value);
            }
        }
    }

    /**
     * foreach等动态标签会生成additionalParameter，普通参数再从Map或嵌套属性中读取。
     */
    private Object getParameterValue(BoundSql boundSql, Map<String, Object> paramsMap, String property) {
        if (boundSql.hasAdditionalParameter(property)) {
            return boundSql.getAdditionalParameter(property);
        }
        if (paramsMap.containsKey(property)) {
            return paramsMap.get(property);
        }
        MetaObject metaObject = myBatisConfiguration.newMetaObject(paramsMap);
        return metaObject.getValue(property);
    }

    /**
     * 只有开启事务时才显式回滚，未开启时保留数据库默认自动提交语义。
     */
    private void rollbackQuietly(Connection connection, boolean transactionEnabled) {
        if (!transactionEnabled || connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            log.error("多脚本SQL事务回滚失败", rollbackException);
        }
    }

    /**
     * 连接来自池，归还前恢复自动提交状态，避免影响后续复用连接。
     */
    private void restoreAutoCommit(Connection connection, boolean autoCommitChanged, boolean oldAutoCommit) {
        if (connection == null || !autoCommitChanged) {
            return;
        }
        try {
            connection.setAutoCommit(oldAutoCommit);
        } catch (SQLException autoCommitException) {
            log.error("恢复连接自动提交状态失败", autoCommitException);
        }
    }

    /**
     * 批量DML自行持有连接，执行结束必须归还连接池。
     */
    private void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException closeException) {
            log.error("关闭多脚本SQL连接失败", closeException);
        }
    }

    private boolean checkPage(String sql) {
        String[] patterns = {
            "(?i)limit.*?\\d",      // mysql,tidb
            "(?i)offset.*?\\d",     // postgres, sqlserver2012+
            "(?i)ROWNUM.*?\\d"      // oracle
        };

        for (String pattern : patterns) {
            if (Pattern.compile(pattern).matcher(sql).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * SQL安全验证：检查危险的SQL模式
     * 防止通过动态SQL执行危险操作
     */
    private void validateSqlSafety(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL语句不能为空");
        }

        String upperSql = sql.toUpperCase();

        // 1. 检查是否包含多条语句
        if (sql.contains(";") && !upperSql.contains("BEGIN") && !upperSql.contains("DECLARE")) {
            // 检查是否是注释后的分号
            String[] statements = sql.split(";");
            if (statements.length > 1) {
                for (String stmt : statements) {
                    String trimmed = stmt.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("--") && !trimmed.startsWith("/*")) {
                        log.warn("检测到可能的多语句SQL: {}", truncateSql(sql));
                    }
                }
            }
        }

        // 2. 检查危险的系统命令
        String[] dangerousPatterns = {
            "EXEC\\s+XP_",          // SQL Server系统存储过程
            "EXEC\\s+SP_",          // SQL Server系统存储过程
            "XP_CMDSHELL",          // SQL Server命令执行
            "INTO\\s+OUTFILE",      // MySQL文件写入
            "INTO\\s+DUMPFILE",     // MySQL文件写入
            "LOAD_FILE",            // MySQL文件读取
        };

        for (String pattern : dangerousPatterns) {
            if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(upperSql).find()) {
                log.error("检测到危险SQL模式: {}, SQL: {}", pattern, truncateSql(sql));
                throw new IllegalArgumentException("SQL包含不允许的操作");
            }
        }

        // 3. SQL长度限制（防止超长SQL导致问题）
        if (sql.length() > 50000) {
            throw new IllegalArgumentException("SQL语句长度超过限制: " + sql.length());
        }
    }

    /**
     * 截断SQL用于日志输出，避免记录超长SQL
     */
    private String truncateSql(String sql) {
        if (sql == null) {
            return "";
        }
        int maxLength = 200;
        if (sql.length() <= maxLength) {
            return sql;
        }
        return sql.substring(0, maxLength) + "... (总长度: " + sql.length() + ")";
    }
}
