package cn.crabc.core.datasource.driver.jdbc;

import cn.crabc.core.datasource.config.JdbcDataSourceRouter;
import cn.crabc.core.datasource.exception.CustomException;
import cn.crabc.core.spi.MetaDataMapper;
import cn.crabc.core.spi.bean.Column;
import cn.crabc.core.spi.bean.Schema;
import cn.crabc.core.spi.bean.Table;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class JdbcMetaData implements MetaDataMapper {

    @Override
    public List<Schema> getCatalogs(String dataSourceId) {
        List<Schema> catalogs = new ArrayList<>();
        try (Connection connection = JdbcDataSourceRouter.getDataSource(dataSourceId).getConnection();
             ResultSet resultSet = connection.getMetaData().getCatalogs()) {
            boolean duckDb = isDuckDb(connection);
            while (resultSet.next()) {
                String schemaName = resultSet.getString(1);
                if (isSystemSchema(schemaName) || (duckDb && isDuckDbSystemCatalog(schemaName))) {
                    continue;
                }
                Schema schema = new Schema();
                schema.setSchema(schemaName);
                schema.setCatalog(schemaName);
                catalogs.add(schema);
            }
            return catalogs;
        } catch (Exception e) {
            throw new IllegalStateException("查询catalogs失败", e);
        }
    }

    @Override
    public List<Schema> getSchemas(String dataSourceId, String catalog) {
        List<Schema> schemas = new ArrayList<>();
        try (Connection connection = JdbcDataSourceRouter.getDataSource(dataSourceId).getConnection();
             ResultSet resultSet = connection.getMetaData().getSchemas(catalog, null)) {
            while (resultSet.next()) {
                String schemaName = resultSet.getString(1);
                if (isSystemSchema(schemaName)) {
                    continue;
                }
                Schema schema = new Schema();
                schema.setSchema(schemaName);
                schema.setCatalog(catalog);
                schemas.add(schema);
            }
            return schemas;
        } catch (Exception e) {
            throw new CustomException(51002, "查询schema失败，请检查数据源是否正确");
        }
    }

    @Override
    public List<Table> getTables(String dataSourceId, String catalog, String schema) {
        List<Table> tables = new ArrayList<>();
        String[] tableType = {"TABLE", "VIEW"};
        try (Connection connection = JdbcDataSourceRouter.getDataSource(dataSourceId).getConnection()) {
            // 获取表和视图
            try (ResultSet resultSet = connection.getMetaData().getTables(catalog, schema, "%", tableType)) {
                while (resultSet.next()) {
                    tables.add(buildTable(resultSet, schema));
                }
            }
            
            // 获取存储过程（过滤掉函数，函数通过getFunctions获取）
            try (ResultSet procedures = connection.getMetaData().getProcedures(catalog, schema, null)) {
                while (procedures.next()) {
                    Table procedure = buildProcedure(procedures, schema);
                    tables.add(procedure);
                }
            } catch (Exception ignored) {}

            return tables;
        } catch (Exception e) {
            throw new CustomException(51003, "查询table失败，请检查数据源是否正确");
        }
    }

    @Override
    public List<Column> getColumns(String dataSourceId, String catalog, String schema, String table) {
        List<Column> columns = new ArrayList<>();
        try (Connection connection = JdbcDataSourceRouter.getDataSource(dataSourceId).getConnection();
             ResultSet resultSet = connection.getMetaData().getColumns(catalog, schema, table, null)) {
            while (resultSet.next()) {
                columns.add(buildColumn(resultSet, schema, table));
            }
            return columns;
        } catch (Exception e) {
            throw new CustomException(51004, "查询字段失败，请检查数据源是否正确");
        }
    }

    private boolean isSystemSchema(String schemaName) {
        return "information_schema".equalsIgnoreCase(schemaName) 
            || "performance_schema".equalsIgnoreCase(schemaName) 
            || "pg_catalog".equals(schemaName);
    }

    private boolean isDuckDb(Connection connection) throws SQLException {
        return "DuckDB".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName());
    }

    /**
     * DuckDB 的 system/temp 是引擎内部 catalog（temp 用于临时表），不属于业务库。
     */
    private boolean isDuckDbSystemCatalog(String catalogName) {
        return "system".equalsIgnoreCase(catalogName) || "temp".equalsIgnoreCase(catalogName);
    }

    private Table buildTable(ResultSet rs, String schema) throws SQLException {
        Table table = new Table();
        table.setTableName(rs.getString("TABLE_NAME"));
        table.setRemarks(rs.getString("REMARKS")); 
        table.setTableType(rs.getString("TABLE_TYPE"));
        table.setCatalog(rs.getString("TABLE_CAT"));
        table.setSchema(schema);
        return table;
    }

    /**
     * 构建存储过程对象
     * 通过PROCEDURE_TYPE过滤函数（函数通过getFunctions单独获取，避免重复）
     * PROCEDURE_TYPE: 0=未知, 1=存储过程(无返回值), 2=函数(有返回值)
     */
    private Table buildProcedure(ResultSet rs, String schema) throws SQLException {
        int procedureType = rs.getInt("PROCEDURE_TYPE");
        Table table = new Table();
        table.setTableName(rs.getString("PROCEDURE_NAME"));
        table.setRemarks(rs.getString("REMARKS"));
        table.setTableType(procedureType == 2 ? "FUNCTION" : "PROCEDURE");
        table.setCatalog(rs.getString("PROCEDURE_CAT"));
        table.setSchema(schema);
        return table;
    }

    private Column buildColumn(ResultSet rs, String schema, String table) throws SQLException {
        Column column = new Column();
        column.setColumnName(rs.getString("COLUMN_NAME"));
        column.setRemarks(rs.getString("REMARKS"));
        column.setColumnType(rs.getString("TYPE_NAME"));
        column.setColumnSize(rs.getString("COLUMN_SIZE"));
        column.setColumnDefault(rs.getString("COLUMN_DEF"));
        column.setDecimalDigits(rs.getString("DECIMAL_DIGITS"));
        column.setCatalog(rs.getString("TABLE_CAT"));
        column.setSchema(schema);
        column.setTableName(table);
        
        String columnType = column.getColumnType() == null ? "" : column.getColumnType().toUpperCase();
        if (columnType.contains("DATE") || columnType.contains("TIME")) {
            column.setTypeIcon("date");
        } else if (columnType.contains("INT") || columnType.contains("NUMBER")
                || columnType.contains("FLOAT") || columnType.contains("DECIMAL")) {
            column.setTypeIcon("int");
        } else {
            column.setTypeIcon("str");
        }
        
        return column;
    }
}
