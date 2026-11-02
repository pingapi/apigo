package cn.crabc.core.app.service.core;

import cn.crabc.core.datasource.constant.BaseConstant;
import cn.crabc.core.datasource.driver.DataSourceManager;
import cn.crabc.core.spi.MetaDataMapper;
import cn.crabc.core.spi.bean.Column;
import cn.crabc.core.spi.bean.Table;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 元数据查询服务（带缓存）。
 * <p>
 * 缓存必须放在<b>独立 Bean</b> 上：归属校验在 Controller 层先执行，
 * 缓存命中时方法体不执行，如果把校验与查询写在同一个方法里，
 * 任意登录用户都能借缓存命中绕过校验读取他人数据源的表结构。
 * <p>
 * 缓存失效：数据源 update/delete 时由 {@code BaseDataSourceServiceImpl} 整体清理。
 *
 * @author yuqf
 */
@Service
public class MetaDataQueryService {

    @Autowired
    private DataSourceManager dataSourceManager;

    /**
     * 查询 schema/catalog 列表。
     *
     * @param datasourceId   数据源ID
     * @param datasourceType 数据源类型（catalog 型数据源查 catalogs）
     * @param catalog        catalog 名称（仅非 catalog 型时使用）
     * @return schema/catalog 名称列表
     */
    @Cacheable(cacheNames = "schemaCache", cacheManager = "metaDataManager", key = "#datasourceId+'_'+#catalog")
    public List getSchemas(String datasourceId, String datasourceType, String catalog) {
        MetaDataMapper metaData = dataSourceManager.getMetaData(datasourceId);
        if (BaseConstant.CATALOG_DATA_SOURCE.contains(datasourceType)) {
            return metaData.getCatalogs(datasourceId);
        }
        return metaData.getSchemas(datasourceId, catalog);
    }

    /**
     * 查询表列表。
     *
     * @param datasourceId   数据源ID
     * @param schema         schema/catalog 名称
     * @param datasourceType 数据源类型
     * @return 表元数据列表
     */
    @Cacheable(cacheNames = "tableCache", cacheManager = "metaDataManager", key = "#datasourceId+'_'+#schema")
    public List<Table> getTables(String datasourceId, String schema, String datasourceType) {
        MetaDataMapper metaData = dataSourceManager.getMetaData(datasourceId);
        if (BaseConstant.CATALOG_DATA_SOURCE.contains(datasourceType)) {
            return metaData.getTables(datasourceId, schema, null);
        }
        return metaData.getTables(datasourceId, null, schema);
    }

    /**
     * 查询字段列表。
     *
     * @param datasourceId   数据源ID
     * @param schema         schema/catalog 名称
     * @param table          表名
     * @param datasourceType 数据源类型
     * @return 字段元数据列表
     */
    @Cacheable(cacheNames = "columnsCache", cacheManager = "metaDataManager", key = "#datasourceId+'_'+#schema+'_'+#table")
    public List<Column> getColumns(String datasourceId, String schema, String table, String datasourceType) {
        MetaDataMapper metaData = dataSourceManager.getMetaData(datasourceId);
        if (BaseConstant.CATALOG_DATA_SOURCE.contains(datasourceType)) {
            return metaData.getColumns(datasourceId, schema, null, table);
        }
        return metaData.getColumns(datasourceId, null, schema, table);
    }
}
