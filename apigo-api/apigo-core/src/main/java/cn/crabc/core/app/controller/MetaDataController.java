package cn.crabc.core.app.controller;

import cn.crabc.core.app.entity.BaseDatasource;
import cn.crabc.core.app.service.core.MetaDataQueryService;
import cn.crabc.core.app.service.system.IBaseDataSourceService;
import cn.crabc.core.app.util.ResourceOwnerGuard;
import cn.crabc.core.app.util.Result;
import cn.crabc.core.app.util.SQLUtil;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import cn.crabc.core.spi.bean.Column;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 元数据管理
 *
 * @author yuqf
 */
@RestController
@RequestMapping("/api/box/sys/metadata/")
public class MetaDataController {

    @Autowired
    private IBaseDataSourceService baseDataSourceService;
    @Autowired
    private ResourceOwnerGuard ownerGuard;
    @Autowired
    private MetaDataQueryService metaDataQueryService;

    /**
     * 数据源列表
     *
     * @param name
     * @return
     */
    @GetMapping("/dataSources")
    public Result dataSources(String name) {
        List<BaseDatasource> dataSourceList = baseDataSourceService.getDataSourceList(name);
        Map<String, List<BaseDatasource>> listMap = dataSourceList.stream().collect(Collectors.groupingBy(BaseDatasource::getDatasourceType));
        return Result.success(listMap);
    }

    // 归属校验必须发生在缓存查询之前：缓存放在 MetaDataQueryService（独立 Bean），
    // 若与校验写在同一方法里，缓存命中时方法体不执行，校验会被绕过
    @GetMapping("/schemas")
    public Result getSchemas(@RequestParam("datasourceId") String datasourceId,String datasourceType,  String catalog) {
        requireOwnedDatasource(datasourceId);
        return Result.success(metaDataQueryService.getSchemas(datasourceId, datasourceType, catalog));
    }

    @GetMapping("/tables")
    public Result getTables(@RequestParam("datasourceId") String datasourceId, @RequestParam("schema") String schema,String datasourceType) {
        requireOwnedDatasource(datasourceId);
        return Result.success(metaDataQueryService.getTables(datasourceId, schema, datasourceType));
    }

    @GetMapping("/columns")
    public Result getColumns(@RequestParam("datasourceId") String datasourceId, @RequestParam("schema") String schema, @RequestParam("table") String table, String datasourceType) {
        requireOwnedDatasource(datasourceId);
        return Result.success(metaDataQueryService.getColumns(datasourceId, schema, table, datasourceType));
    }

    /**
     * 元数据只能查看自己的数据源：否则可遍历他人库的 schema/表/字段结构。
     *
     * @param datasourceId 数据源ID（字符串）
     */
    private void requireOwnedDatasource(String datasourceId) {
        try {
            ownerGuard.assertDataSource(Integer.valueOf(datasourceId.trim()));
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), "数据源ID不合法：" + datasourceId);
        }
    }

    /**
     * 脚本模版
     * @param datasourceId
     * @param schema
     * @param table
     * @return
     */
    @GetMapping("/script")
    public Result getScript(String datasourceId, String schema, String table,String datasourceType) {
        if (datasourceId != null && !datasourceId.isBlank()) {
            requireOwnedDatasource(datasourceId);
        }
        List<String> columnName = new ArrayList<>();
        if (table == null || "".equals(table)) {
            table = "t_user";
            columnName.add("id");
            columnName.add("user_name");
            columnName.add("create_time");
            columnName.add("create_by");
        }else{
            List<Column> columns = metaDataQueryService.getColumns(datasourceId, schema, table, datasourceType);
            columnName = columns.stream().map(Column::getColumnName).collect(Collectors.toList());
        }
        return Result.success(getSqlTemplate(columnName, table));
    }

    /**
     * 通用SQL拼接
     * @param columnName
     * @param table
     * @return
     */
    private List<Map<String,Object>> getSqlTemplate(List<String> columnName, String table){
        String Str = String.join(",",columnName);
        List<Map<String,Object>> list = new ArrayList<>();

        Map<String,Object> select = new HashMap<>();
        String sql = "select " + Str + " from " + table;
        select.put("type", "Select");
        select.put("value", sql);
        list.add(select);

        Map<String,Object> insert = new HashMap<>();
        StringBuffer bufferSql = new StringBuffer();
        bufferSql.append("insert into " +table +" values (");
        for (int i = 0; i < columnName.size(); i++) {
            if (i == columnName.size() - 1){
                bufferSql.append("#{"+ SQLUtil.underlineToCamel(columnName.get(i))+"})");
            }else{
                bufferSql.append("#{"+SQLUtil.underlineToCamel(columnName.get(i))+"}, ");
            }
        }
        insert.put("type", "Insert");
        insert.put("value", bufferSql);
        list.add(insert);

        Map<String,Object> update = new HashMap<>();
        StringBuffer updateSql = new StringBuffer();
        updateSql.append("update " +table +" set ");
        for (int i = 0; i < columnName.size(); i++) {
            if (i == columnName.size() - 1){
                updateSql.append(columnName.get(i)+ "= #{"+SQLUtil.underlineToCamel(columnName.get(i))+"}");
            }else{
                updateSql.append(columnName.get(i)+ "= #{"+SQLUtil.underlineToCamel(columnName.get(i))+"},");
            }
        }
        updateSql.append(" where id=#{id}");
        update.put("type", "Update");
        update.put("value", updateSql);
        list.add(update);

        Map<String,Object> delete = new HashMap<>();
        String deleteSql = "delete from " + table +" where id=#{id}";
        delete.put("type", "Delete");
        delete.put("value", deleteSql);
        list.add(delete);
        return list;
    }
}
