package cn.crabc.core.app.service.core.impl;


import cn.crabc.core.app.entity.vo.PreviewVO;
import cn.crabc.core.app.service.core.IBaseDataService;
import cn.crabc.core.app.util.SQLUtil;
import cn.crabc.core.datasource.constant.BaseConstant;
import cn.crabc.core.datasource.driver.DataSourceManager;
import cn.crabc.core.datasource.exception.CustomException;
import cn.crabc.core.spi.StatementMapper;
import cn.crabc.core.spi.bean.BaseDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 接口执行服务 实现类
 *
 * @author yuqf
 */
@Service
public class BaseDataServiceImpl implements IBaseDataService {

    @Autowired
    private DataSourceManager dataSourceManager;

    @Override
    public String testConnection(BaseDataSource dataSource) {
        return dataSourceManager.test(dataSource);
    }

    @Override
    public PreviewVO sqlPreview(String datasourceId,String datasourceType, String schema, String sql) {
        return sqlPreview(datasourceId, datasourceType, schema, sql, null);
    }

    @Override
    public PreviewVO sqlPreview(String datasourceId, String datasourceType, String schema, String sql,
                                Integer scriptTimeoutSeconds) {
        Map<String, Object> params = new HashMap<>();
        // 数据源类型
        if (datasourceType != null) {
            params.put(BaseConstant.DATA_SOURCE_TYPE, datasourceType);
        }
        params.put(BaseConstant.BASE_API_EXEC_TYPE, "preview");
        params.put(BaseConstant.PAGE_SETUP, 1);
        // 预览同样遵循接口配置的脚本超时时间，未配置时由执行层回落到平台默认值
        if (scriptTimeoutSeconds != null && scriptTimeoutSeconds > 0) {
            params.put(BaseConstant.SCRIPT_TIMEOUT_SECONDS, scriptTimeoutSeconds);
        }
        Object result = this.execute(datasourceId, datasourceType, schema, sql, params);
        PreviewVO preview = new PreviewVO();
        if (result instanceof List) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) result;
            if (!list.isEmpty()) {
                preview.setData(list);
                preview.setMetadata(list.get(0).keySet());
            }
        } else {
            Map<String, Object> data = new HashMap<>();
            data.put("执行结果", String.format("执行成功，影响条数：%s", result));
            List<Map<String, Object>> list = Collections.singletonList(data);
            preview.setData(list);
            preview.setMetadata(Collections.singleton("执行结果"));
        }
        return preview;
    }

    @Override
    public Object execute(String datasourceId, String datasourceType, String schema, String sql, Map<String, Object> params) {
        StatementMapper statementMapper = dataSourceManager.getStatementMapper(datasourceId);

        if (params == null) {
            params = new HashMap<>();
        }
        if (datasourceType != null && !params.containsKey(BaseConstant.DATA_SOURCE_TYPE)) {
            params.put(BaseConstant.DATA_SOURCE_TYPE, datasourceType);
        }

        List<String> statements = SQLUtil.splitSqlStatements(sql);
        if (statements.size() > 1) {
            if (!params.containsKey(BaseConstant.TRANSACTION_ENABLED)) {
                throw new CustomException(40011, "当前执行入口不支持多脚本SQL");
            }
            SQLUtil.validateBatchDmlStatements(statements);
            return statementMapper.executeBatchDml(datasourceId, schema, statements, params, isTransactionEnabled(params));
        }

        String executeSql = statements.isEmpty() ? sql : statements.get(0);
        String sqlType = SQLUtil.getOperateType(executeSql).toLowerCase();
        switch (sqlType) {
            case "insert":
                return statementMapper.insert(datasourceId, schema, executeSql, params);
            case "update":
                return statementMapper.update(datasourceId, schema, executeSql, params);
            case "delete":
                return statementMapper.delete(datasourceId, schema, executeSql, params);
            default:
                Object pageNum = params.get(BaseConstant.PAGE_NUM);
                Object pageSize = params.get(BaseConstant.PAGE_SIZE);
                if (pageNum != null && pageSize != null) {
                    return statementMapper.selectPage(datasourceId, schema, executeSql, params,
                            Integer.parseInt(pageNum.toString()),
                            Integer.parseInt(pageSize.toString()));
                }
                return statementMapper.selectList(datasourceId, schema, executeSql, params);
        }
    }

    /**
     * 执行层兼容前端1/0、测试Boolean等常见表示，只有明确为1或true时开启事务。
     */
    private boolean isTransactionEnabled(Map<String, Object> params) {
        Object value = params.get(BaseConstant.TRANSACTION_ENABLED);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value != null && "1".equals(value.toString());
    }
}
