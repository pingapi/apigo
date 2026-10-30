package cn.crabc.core.app.service.core;

import cn.crabc.core.app.entity.vo.PreviewVO;
import cn.crabc.core.spi.bean.BaseDataSource;
import java.util.Map;

/**
 * 通用方法服务类
 *
 * @author yuqf
 */
public interface IBaseDataService {
    /**
     * 测试数据库
     *
     * @param dataSource
     * @return
     */
    String testConnection(BaseDataSource dataSource);

    /**
     * SQL预览
     *
     * @param datasourceId
     * @param schema
     * @param sql
     * @return
     */
    PreviewVO sqlPreview(String datasourceId, String datasourceType,String schema, String sql);

    /**
     * SQL预览（指定脚本超时时间）
     *
     * @param datasourceId
     * @param datasourceType
     * @param schema
     * @param sql
     * @param scriptTimeoutSeconds 脚本超时时间（秒），为空或小于等于0时使用平台默认值
     * @return
     */
    default PreviewVO sqlPreview(String datasourceId, String datasourceType, String schema, String sql,
                                 Integer scriptTimeoutSeconds) {
        return sqlPreview(datasourceId, datasourceType, schema, sql);
    }

    /**
     * 测试API
     *
     * @param datasourceId
     * @param schema
     * @param sql
     * @param params
     * @return
     */
    Object execute(String datasourceId,String schema, String datasourceType, String sql, Map<String, Object> params);
}
