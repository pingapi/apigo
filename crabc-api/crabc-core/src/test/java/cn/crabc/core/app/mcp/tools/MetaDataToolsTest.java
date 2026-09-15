package cn.crabc.core.app.mcp.tools;

import cn.crabc.core.app.entity.BaseDatasource;
import cn.crabc.core.app.mcp.McpTestFixture;
import cn.crabc.core.app.service.system.IBaseDataSourceService;
import cn.crabc.core.datasource.driver.DataSourceManager;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import cn.crabc.core.spi.MetaDataMapper;
import cn.crabc.core.spi.bean.Column;
import cn.crabc.core.spi.bean.Schema;
import cn.crabc.core.spi.bean.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 元数据工具测试。
 * <p>
 * 重点：CATALOG 型数据源（mysql 等）的 schema 位置实际传 catalog，
 * 该分支必须与 {@code MetaDataController} 完全一致，否则元数据查询会取到错误的库；
 * 以及截断必须显式回传，避免 AI 误判"只有这些表"。
 *
 * @author yuqf
 */
class MetaDataToolsTest {

    private DataSourceManager dataSourceManager;
    private IBaseDataSourceService dataSourceService;
    private MetaDataMapper<?> metaData;
    private MetaDataTools tools;

    @BeforeEach
    void setUp() {
        dataSourceManager = mock(DataSourceManager.class);
        dataSourceService = mock(IBaseDataSourceService.class);
        metaData = mock(MetaDataMapper.class);
        when(dataSourceManager.getMetaData("3")).thenReturn(metaData);
        tools = new MetaDataTools(dataSourceManager, dataSourceService, McpTestFixture.support());
    }

    @Test
    @DisplayName("库列表：CATALOG 型数据源走 getCatalogs")
    void schemasForCatalogType() {
        when(dataSourceService.getDataSource(3)).thenReturn(datasource("mysql"));
        when(metaData.getCatalogs("3")).thenReturn(List.of("orders", "crm"));

        Map<String, Object> output = tools.schemas("3");

        assertEquals(2, output.get("total"));
        assertEquals(2, output.get("returned"));
        assertEquals(false, output.get("truncated"));
        assertEquals("orders", items(output).get(0).get("schema"));
        verify(metaData).getCatalogs("3");
        verify(metaData, never()).getSchemas(eq("3"), isNull());
    }

    @Test
    @DisplayName("库列表：非 CATALOG 型数据源走 getSchemas")
    void schemasForSchemaType() {
        when(dataSourceService.getDataSource(3)).thenReturn(datasource("postgresql"));
        when(metaData.getSchemas("3", null)).thenReturn(List.of(schema("public", "orders")));

        Map<String, Object> output = tools.schemas("3");

        assertEquals(1, ((List<?>) output.get("items")).size());
        assertEquals("public", items(output).get(0).get("schema"));
        assertEquals("orders", items(output).get(0).get("catalog"));
        verify(metaData, never()).getCatalogs("3");
    }

    @Test
    @DisplayName("表列表：CATALOG 型把 schema 作为 catalog 传入")
    void tablesForCatalogType() {
        when(dataSourceService.getDataSource(3)).thenReturn(datasource("mysql"));
        when(metaData.getTables("3", "orders", null)).thenReturn(List.of(table("t_order", "订单表", "TABLE")));

        Map<String, Object> output = tools.tables("3", "orders");

        assertEquals(1, ((List<?>) output.get("items")).size());
        assertEquals("t_order", items(output).get(0).get("tableName"));
        assertEquals("订单表", items(output).get(0).get("remarks"));
        assertEquals("orders", output.get("schema"));
        verify(metaData).getTables("3", "orders", null);
    }

    @Test
    @DisplayName("表列表：非 CATALOG 型把 schema 作为 schema 传入")
    void tablesForSchemaType() {
        when(dataSourceService.getDataSource(3)).thenReturn(datasource("postgresql"));
        when(metaData.getTables("3", null, "public")).thenReturn(List.of(table("t_order", null, "TABLE")));

        tools.tables("3", "public");

        verify(metaData).getTables("3", null, "public");
    }

    @Test
    @DisplayName("字段列表：CATALOG 型传 catalog，非 CATALOG 型传 schema")
    void columnsBranch() {
        when(dataSourceService.getDataSource(3)).thenReturn(datasource("mysql"));
        when(metaData.getColumns("3", "orders", null, "t_order"))
                .thenReturn(List.of(column("order_id", "bigint", "20", "订单ID")));

        Map<String, Object> output = tools.columns("3", "orders", "t_order");

        assertEquals(1, ((List<?>) output.get("items")).size());
        assertEquals("order_id", items(output).get(0).get("columnName"));
        assertEquals("bigint", items(output).get(0).get("columnType"));
        assertEquals("t_order", output.get("table"));
        verify(metaData).getColumns("3", "orders", null, "t_order");

        when(dataSourceService.getDataSource(3)).thenReturn(datasource("postgresql"));
        when(metaData.getColumns("3", null, "public", "t_order")).thenReturn(List.of());
        tools.columns("3", "public", "t_order");
        verify(metaData).getColumns("3", null, "public", "t_order");
    }

    @Test
    @DisplayName("数据源不存在时报错，且不访问元数据")
    void datasourceNotFound() {
        when(dataSourceService.getDataSource(99)).thenReturn(null);
        CustomException ex = assertThrows(CustomException.class, () -> tools.schemas("99"));
        assertEquals(ErrorStatusEnum.DATASOURCE_NOT_FOUNT.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("datasourceId 非数字时给出可自纠的业务异常，而不是 NumberFormatException")
    void invalidDatasourceId() {
        CustomException ex = assertThrows(CustomException.class, () -> tools.schemas("abc"));
        assertEquals(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), ex.getCode());
        assertTrue(ex.getMsg().contains("datasourceId"));
    }

    @Test
    @DisplayName("元数据条目超过上限时截断，并显式回传 total/truncated")
    void truncateLargeMetadata() {
        when(dataSourceService.getDataSource(3)).thenReturn(datasource("mysql"));
        List<Table> tables = new java.util.ArrayList<>();
        for (int i = 0; i < 260; i++) {
            tables.add(table("t_" + i, null, "TABLE"));
        }
        when(metaData.getTables("3", "orders", null)).thenReturn(tables);

        Map<String, Object> output = tools.tables("3", "orders");

        assertEquals(260, output.get("total"));
        assertEquals(200, output.get("returned"));
        assertEquals(true, output.get("truncated"), "必须显式告知被截断，否则 AI 会误判表数量");
        assertEquals(200, ((List<?>) output.get("items")).size());
        assertTrue(String.valueOf(output.get("tip")).contains("260"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> output) {
        return (List<Map<String, Object>>) output.get("items");
    }

    private BaseDatasource datasource(String type) {
        BaseDatasource datasource = new BaseDatasource();
        datasource.setDatasourceId(3);
        datasource.setDatasourceType(type);
        return datasource;
    }

    private Schema schema(String schema, String catalog) {
        Schema bean = new Schema();
        bean.setSchema(schema);
        bean.setCatalog(catalog);
        return bean;
    }

    private Table table(String name, String remarks, String type) {
        Table bean = new Table();
        bean.setTableName(name);
        bean.setRemarks(remarks);
        bean.setTableType(type);
        return bean;
    }

    private Column column(String name, String type, String size, String remarks) {
        Column bean = new Column();
        bean.setColumnName(name);
        bean.setColumnType(type);
        bean.setColumnSize(size);
        bean.setRemarks(remarks);
        return bean;
    }
}
