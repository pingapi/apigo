package cn.crabc.core.app.mcp.tools;

import cn.crabc.core.app.entity.BaseDatasource;
import cn.crabc.core.app.mcp.support.McpToolSupport;
import cn.crabc.core.app.service.system.IBaseDataSourceService;
import cn.crabc.core.datasource.constant.BaseConstant;
import cn.crabc.core.datasource.driver.DataSourceManager;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import cn.crabc.core.spi.MetaDataMapper;
import cn.crabc.core.spi.bean.Column;
import cn.crabc.core.spi.bean.Schema;
import cn.crabc.core.spi.bean.Table;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MCP 元数据工具：查看库/表/字段结构，是 AI 写 SQL 的前置步骤。
 * <p>
 * 分支逻辑严格对齐 {@code MetaDataController}：CATALOG 型数据源
 * （sybase/mysql/mariadb/doris/starrocks/tidb/tdsql）走 getCatalogs，
 * 且 schema 位置实际传 catalog。
 * <p>
 * 返回体带 {@code total} / {@code truncated}，避免条目被静默截断后 AI 误判"只有这些表"。
 *
 * @author yuqf
 */
@Component
public class MetaDataTools {

    /**
     * 单次返回的最大条目数，防止元数据撑爆模型上下文。
     */
    private static final int MAX_ITEMS = 200;

    private final DataSourceManager dataSourceManager;
    private final IBaseDataSourceService dataSourceService;
    private final McpToolSupport support;

    public MetaDataTools(DataSourceManager dataSourceManager,
                         IBaseDataSourceService dataSourceService,
                         McpToolSupport support) {
        this.dataSourceManager = dataSourceManager;
        this.dataSourceService = dataSourceService;
        this.support = support;
    }

    @McpTool(name = "apigo_metadata_schemas",
            description = """
                    查询指定数据源下的库/schema 列表。
                    前置条件：先通过 apigo_datasource_list 获取 datasourceId。
                    返回 items[].schema，可作为 apigo_metadata_tables 的 schema 参数。
                    对于 mysql 等 CATALOG 型数据源，返回值即数据库名。
                    注意：truncated=true 表示条目超过 200 条被截断。
                    """,
            annotations = @McpTool.McpAnnotations(title = "查询库/schema 列表", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> schemas(
            @McpToolParam(description = "数据源ID", required = true) String datasourceId) {
        return support.bindUser("apigo_metadata_schemas", () -> {
            String id = support.requireText(datasourceId, "datasourceId");
            MetaDataMapper<?> metaData = dataSourceManager.getMetaData(id);
            List<Map<String, Object>> items = new ArrayList<>();
            int total;
            if (isCatalogType(dataSourceType(id))) {
                List<Object> catalogs = metaData.getCatalogs(id);
                total = catalogs == null ? 0 : catalogs.size();
                if (catalogs != null) {
                    for (Object catalog : catalogs) {
                        items.add(McpToolSupport.result("schema", String.valueOf(catalog)));
                        if (items.size() >= MAX_ITEMS) {
                            break;
                        }
                    }
                }
            } else {
                List<Schema> schemas = metaData.getSchemas(id, null);
                total = schemas == null ? 0 : schemas.size();
                if (schemas != null) {
                    for (Schema schema : schemas) {
                        Map<String, Object> item = McpToolSupport.newResult();
                        McpToolSupport.putIfNotNull(item, "schema", schema.getSchema());
                        McpToolSupport.putIfNotNull(item, "catalog", schema.getCatalog());
                        items.add(item);
                        if (items.size() >= MAX_ITEMS) {
                            break;
                        }
                    }
                }
            }
            return metaResult(id, null, null, items, total);
        });
    }

    @McpTool(name = "apigo_metadata_tables",
            description = """
                    查询指定库/schema 下的表列表（表名、表注释、表类型）。
                    前置条件：datasourceId 来自 apigo_datasource_list，schema 来自 apigo_metadata_schemas。
                    返回 items[].tableName；truncated=true 表示表数量超过 200 被截断，此时请先用 SQL 或更精确的范围定位。
                    如需进一步了解结构，再用 apigo_metadata_columns。
                    """,
            annotations = @McpTool.McpAnnotations(title = "查询表列表", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> tables(
            @McpToolParam(description = "数据源ID", required = true) String datasourceId,
            @McpToolParam(description = "库/schema 名称", required = true) String schema) {
        return support.bindUser("apigo_metadata_tables", () -> {
            String id = support.requireText(datasourceId, "datasourceId");
            String schemaName = support.requireText(schema, "schema");
            MetaDataMapper<?> metaData = dataSourceManager.getMetaData(id);
            List<Table> tables = isCatalogType(dataSourceType(id))
                    ? metaData.getTables(id, schemaName, null)
                    : metaData.getTables(id, null, schemaName);

            List<Map<String, Object>> items = new ArrayList<>();
            int total = tables == null ? 0 : tables.size();
            if (tables != null) {
                for (Table table : tables) {
                    Map<String, Object> item = McpToolSupport.newResult();
                    McpToolSupport.putIfNotNull(item, "tableName", table.getTableName());
                    McpToolSupport.putIfNotNull(item, "remarks", table.getRemarks());
                    McpToolSupport.putIfNotNull(item, "tableType", table.getTableType());
                    items.add(item);
                    if (items.size() >= MAX_ITEMS) {
                        break;
                    }
                }
            }
            return metaResult(id, schemaName, null, items, total);
        });
    }

    @McpTool(name = "apigo_metadata_columns",
            description = """
                    查询指定表的字段列表（字段名、类型、长度、注释）。
                    用于在编写 SQL 前确认字段名，避免猜字段。
                    返回 items[].columnName；truncated=true 表示字段数超过 200 被截断。
                    """,
            annotations = @McpTool.McpAnnotations(title = "查询字段列表", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> columns(
            @McpToolParam(description = "数据源ID", required = true) String datasourceId,
            @McpToolParam(description = "库/schema 名称", required = true) String schema,
            @McpToolParam(description = "表名", required = true) String table) {
        return support.bindUser("apigo_metadata_columns", () -> {
            String id = support.requireText(datasourceId, "datasourceId");
            String schemaName = support.requireText(schema, "schema");
            String tableName = support.requireText(table, "table");
            MetaDataMapper<?> metaData = dataSourceManager.getMetaData(id);
            List<Column> columns = isCatalogType(dataSourceType(id))
                    ? metaData.getColumns(id, schemaName, null, tableName)
                    : metaData.getColumns(id, null, schemaName, tableName);

            List<Map<String, Object>> items = new ArrayList<>();
            int total = columns == null ? 0 : columns.size();
            if (columns != null) {
                for (Column column : columns) {
                    Map<String, Object> item = McpToolSupport.newResult();
                    McpToolSupport.putIfNotNull(item, "columnName", column.getColumnName());
                    McpToolSupport.putIfNotNull(item, "columnType", column.getColumnType());
                    McpToolSupport.putIfNotNull(item, "columnSize", column.getColumnSize());
                    McpToolSupport.putIfNotNull(item, "remarks", column.getRemarks());
                    items.add(item);
                    if (items.size() >= MAX_ITEMS) {
                        break;
                    }
                }
            }
            return metaResult(id, schemaName, tableName, items, total);
        });
    }

    /**
     * 统一元数据返回体：显式告知总数与是否被截断，避免模型把截断结果当成全量。
     */
    private Map<String, Object> metaResult(String datasourceId, String schema, String table,
                                           List<Map<String, Object>> items, int total) {
        Map<String, Object> output = McpToolSupport.newResult();
        output.put("datasourceId", datasourceId);
        McpToolSupport.putIfNotNull(output, "schema", schema);
        McpToolSupport.putIfNotNull(output, "table", table);
        output.put("total", total);
        output.put("returned", items.size());
        if (total > items.size()) {
            output.put("truncated", true);
            output.put("tip", "结果共 " + total + " 条，超过单次上限 " + MAX_ITEMS + " 条，仅返回前 "
                    + items.size() + " 条，请用更精确的条件缩小范围");
        } else {
            output.put("truncated", false);
        }
        output.put("items", items);
        return output;
    }

    private boolean isCatalogType(String datasourceType) {
        return datasourceType != null
                && BaseConstant.CATALOG_DATA_SOURCE.contains(datasourceType.toLowerCase(Locale.ROOT));
    }

    private String dataSourceType(String datasourceId) {
        BaseDatasource dataSource = dataSourceService.getDataSource(support.requireInteger(datasourceId, "datasourceId"));
        if (dataSource == null) {
            throw new CustomException(ErrorStatusEnum.DATASOURCE_NOT_FOUNT.getCode(), "无效的数据源：" + datasourceId);
        }
        return dataSource.getDatasourceType();
    }
}
