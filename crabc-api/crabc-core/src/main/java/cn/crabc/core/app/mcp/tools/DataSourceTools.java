package cn.crabc.core.app.mcp.tools;

import cn.crabc.core.app.entity.BaseDatasource;
import cn.crabc.core.app.mcp.support.DatasourceGuard;
import cn.crabc.core.app.mcp.support.McpToolSupport;
import cn.crabc.core.app.service.system.IBaseDataSourceService;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import cn.crabc.core.datasource.util.PageInfo;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MCP 数据源工具。
 * <p>
 * 关键约定（与 Web 控制台保持一致，见 {@code DataSourceEdit.vue} 与 {@code BaseDataSourceServiceImpl}）：
 * <ul>
 *     <li>平台只认 jdbcUrl，不按 host/port 拼装，本层负责按类型模板拼装；</li>
 *     <li>password 入库前必须 Base64 编码，因为 addCache/test 内部会做 Base64 解码；</li>
 *     <li>datasourceType 为 custom 时必须直接提供完整 jdbcUrl。</li>
 * </ul>
 * 安全约定（见 {@link DatasourceGuard}）：数据源类型与 JDBC 协议必须落在白名单内，
 * 且禁止携带 h2 INIT / mysql autoDeserialize 等可在服务端执行代码或读取本地文件的连接参数。
 *
 * @author yuqf
 */
@Component
public class DataSourceTools {

    private static final String TEST_SUCCESS = "1";

    private final IBaseDataSourceService dataSourceService;
    private final McpToolSupport support;
    private final DatasourceGuard guard;

    public DataSourceTools(IBaseDataSourceService dataSourceService, McpToolSupport support, DatasourceGuard guard) {
        this.dataSourceService = dataSourceService;
        this.support = support;
        this.guard = guard;
    }

    @McpTool(name = "apigo_datasource_list",
            description = """
                    分页查询平台已接入的数据源，返回 datasourceId、名称、类型、地址等（密码恒为 ******）。
                    用途：获取 datasourceId，供 apigo_metadata_* / apigo_sql_preview / apigo_api_create 使用。
                    keyword 对数据源名称做模糊匹配。
                    """,
            annotations = @McpTool.McpAnnotations(title = "查询数据源列表", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> datasourceList(
            @McpToolParam(description = "数据源名称关键字，可选", required = false) String keyword,
            @McpToolParam(description = "页码，从 1 开始", required = false) Integer pageNum,
            @McpToolParam(description = "每页条数，默认 10，上限 20", required = false) Integer pageSize) {
        return support.bindUser("apigo_datasource_list", () -> {
            int num = support.pageNum(pageNum);
            int size = support.pageSize(pageSize);
            PageInfo<BaseDatasource> page = dataSourceService.getDataSourcePage(keyword, num, size);
            return pageResult(page, num, size);
        });
    }

    @McpTool(name = "apigo_datasource_get",
            description = """
                    查询单个数据源的详情（返回 datasourceType 等信息，密码不回传）。
                    用途：当只知道 datasourceId、需要确认其类型时使用。
                    """,
            annotations = @McpTool.McpAnnotations(title = "查询数据源详情", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> datasourceGet(
            @McpToolParam(description = "数据源ID", required = true) String datasourceId) {
        return support.bindUser("apigo_datasource_get", () -> {
            BaseDatasource dataSource = requireExisting(support.requireInteger(datasourceId, "datasourceId"));
            return toDatasource(dataSource);
        });
    }

    @McpTool(name = "apigo_datasource_test",
            description = """
                    测试数据库连通性，不落库、无副作用。
                    返回 testPassed=true 表示连通；失败时返回数据库驱动的原始错误信息。
                    前置条件：datasourceType 与 host/port/databaseName（或直接给 jdbcUrl）+ 账号密码。
                    安全约束：仅允许平台支持的数据库协议，嵌入式/文件型引擎与危险连接参数一律拒绝。
                    """,
            annotations = @McpTool.McpAnnotations(title = "测试数据源连通性", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> datasourceTest(
            @McpToolParam(description = "数据源类型：mysql/postgresql/oracle/sqlserver/dm/clickhouse/doris/starrocks/tidb 等", required = true) String datasourceType,
            @McpToolParam(description = "数据库主机地址", required = false) String host,
            @McpToolParam(description = "端口，缺省使用该类型默认端口", required = false) String port,
            @McpToolParam(description = "库名/schema", required = false) String databaseName,
            @McpToolParam(description = "数据库用户名", required = true) String username,
            @McpToolParam(description = "数据库密码（明文，平台侧编码后仅用于建连）", required = true) String password,
            @McpToolParam(description = "完整 JDBC URL，提供后忽略 host/port/databaseName", required = false) String jdbcUrl) {
        return support.bindUser("apigo_datasource_test", () -> {
            BaseDatasource dataSource = buildDatasource(null, null, datasourceType, host, port, databaseName,
                    username, password, null, jdbcUrl);
            String result = dataSourceService.test(dataSource);
            Map<String, Object> output = McpToolSupport.newResult();
            boolean success = TEST_SUCCESS.equals(result);
            output.put("testPassed", success);
            output.put("jdbcUrl", dataSource.getJdbcUrl());
            McpToolSupport.putIfNotNull(output, "message", success ? "连接成功" : result);
            return output;
        });
    }

    @McpTool(name = "apigo_datasource_create",
            description = """
                    在平台新增一个数据库数据源。执行顺序：先测试连通性，通过后落库并加载连接池。
                    失败语义：连通性测试失败时不会落库，可直接修正参数后重试。
                    返回 datasourceId，可用于后续 apigo_metadata_tables / apigo_sql_preview / apigo_api_create。
                    注意：密码会进入本次 AI 上下文，建议使用只读账号；datasourceType=custom 时必须提供完整 jdbcUrl。
                    安全约束：仅允许平台支持的数据库协议（不允许 h2 等嵌入式引擎），且连接串不得携带
                    INIT/autoDeserialize 之类的危险参数。
                    """,
            annotations = @McpTool.McpAnnotations(title = "新增数据源", readOnlyHint = false,
                    destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Map<String, Object> datasourceCreate(
            @McpToolParam(description = "数据源显示名称", required = true) String datasourceName,
            @McpToolParam(description = "数据源类型：mysql/postgresql/oracle/sqlserver/dm/clickhouse/doris/starrocks/tidb/custom 等", required = true) String datasourceType,
            @McpToolParam(description = "数据库主机地址", required = false) String host,
            @McpToolParam(description = "端口，缺省使用该类型默认端口", required = false) String port,
            @McpToolParam(description = "库名/schema", required = false) String databaseName,
            @McpToolParam(description = "数据库用户名", required = true) String username,
            @McpToolParam(description = "数据库密码（明文）", required = true) String password,
            @McpToolParam(description = "备注/描述", required = false) String remarks,
            @McpToolParam(description = "完整 JDBC URL，提供后忽略 host/port/databaseName", required = false) String jdbcUrl) {
        return support.bindWrite("apigo_datasource_create",
                "datasourceName=" + datasourceName + " type=" + datasourceType, () -> {
                    BaseDatasource dataSource = buildDatasource(null, support.requireText(datasourceName, "datasourceName"),
                            datasourceType, host, port, databaseName, username, password, remarks, jdbcUrl);

                    // 先测后建：test 内部同样会做 Base64 解码，编码在 buildDatasource 内完成
                    String testResult = dataSourceService.test(dataSource);
                    if (!TEST_SUCCESS.equals(testResult)) {
                        throw new CustomException(ErrorStatusEnum.DATASOURCE_NOT_FOUNT.getCode(),
                                "数据源连接测试失败，未落库：" + testResult);
                    }
                    dataSourceService.addDataSource(dataSource);

                    Map<String, Object> output = McpToolSupport.newResult();
                    McpToolSupport.putIfNotNull(output, "datasourceId", dataSource.getDatasourceId());
                    McpToolSupport.putIfNotNull(output, "datasourceName", dataSource.getDatasourceName());
                    McpToolSupport.putIfNotNull(output, "datasourceType", dataSource.getDatasourceType());
                    McpToolSupport.putIfNotNull(output, "jdbcUrl", dataSource.getJdbcUrl());
                    output.put("testPassed", true);
                    return output;
                });
    }

    @McpTool(name = "apigo_datasource_update",
            description = """
                    修改已有数据源。未传的字段保留原值；密码不传表示不修改。
                    执行顺序：先按新配置测试连通性（仅当连接信息变化时），通过后才落库并重建连接池，
                    避免把线上数据源改成不可用配置。
                    失败语义：连通性测试失败时不会落库，可直接修正参数后重试。
                    """,
            annotations = @McpTool.McpAnnotations(title = "修改数据源", readOnlyHint = false,
                    destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> datasourceUpdate(
            @McpToolParam(description = "数据源ID", required = true) String datasourceId,
            @McpToolParam(description = "数据源显示名称", required = false) String datasourceName,
            @McpToolParam(description = "数据源类型", required = false) String datasourceType,
            @McpToolParam(description = "数据库主机地址", required = false) String host,
            @McpToolParam(description = "端口", required = false) String port,
            @McpToolParam(description = "库名/schema", required = false) String databaseName,
            @McpToolParam(description = "数据库用户名", required = false) String username,
            @McpToolParam(description = "数据库密码（明文），不传表示不修改", required = false) String password,
            @McpToolParam(description = "备注/描述", required = false) String remarks,
            @McpToolParam(description = "完整 JDBC URL", required = false) String jdbcUrl) {
        return support.bindWrite("apigo_datasource_update", "datasourceId=" + datasourceId, () -> {
            Integer id = support.requireInteger(datasourceId, "datasourceId");
            BaseDatasource current = requireExisting(id);

            // 未传类型时沿用库中原值（历史数据可能是白名单外的类型，不应因改备注而失败）；
            // 新传入的类型必须落在白名单内。
            String type = isBlank(datasourceType) ? current.getDatasourceType() : guard.requireSupportedType(datasourceType);
            // 仅校验本次新提交的连接串；未变更的连接串沿用库中原值，避免历史配置被误判
            String newJdbcUrl = isBlank(jdbcUrl) && isBlank(host)
                    ? current.getJdbcUrl()
                    : guard.validateJdbcUrl(type, support.buildJdbcUrl(type, host, port, databaseName, jdbcUrl));

            // 密码：不传时沿用库里的 Base64 原值；库里为空则要求显式传入，避免 decode(null)
            String encodedPassword;
            if (isBlank(password)) {
                encodedPassword = current.getPassword();
                if (isBlank(encodedPassword)) {
                    throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(),
                            "该数据源当前没有可用密码，请显式传入 password 参数");
                }
            } else {
                encodedPassword = support.encodePassword(password);
            }

            BaseDatasource dataSource = new BaseDatasource();
            dataSource.setDatasourceId(id);
            dataSource.setDatasourceName(isBlank(datasourceName) ? current.getDatasourceName() : datasourceName.trim());
            dataSource.setDatasourceType(type);
            dataSource.setJdbcUrl(newJdbcUrl);
            dataSource.setHost(isBlank(host) ? current.getHost() : host.trim());
            dataSource.setPort(isBlank(port) ? current.getPort() : port.trim());
            dataSource.setUsername(isBlank(username) ? current.getUsername() : username.trim());
            dataSource.setPassword(encodedPassword);
            // updateDataSource 的 SQL 中 remarks 是无条件赋值，必须回填原值否则会被清空
            dataSource.setRemarks(isBlank(remarks) ? current.getRemarks() : remarks);
            dataSource.setClassify(current.getClassify());
            dataSource.setMinIdle(current.getMinIdle());
            dataSource.setMaxActive(current.getMaxActive());
            dataSource.setConnectTimeout(current.getConnectTimeout());
            dataSource.setIdleTimeout(current.getIdleTimeout());
            dataSource.setMaxLifetime(current.getMaxLifetime());
            dataSource.setKeepaliveTime(current.getKeepaliveTime());

            boolean connectionChanged = !Objects.equals(newJdbcUrl, current.getJdbcUrl())
                    || !Objects.equals(dataSource.getUsername(), current.getUsername())
                    || !Objects.equals(encodedPassword, current.getPassword());
            if (connectionChanged) {
                String testResult = dataSourceService.test(dataSource);
                if (!TEST_SUCCESS.equals(testResult)) {
                    throw new CustomException(ErrorStatusEnum.DATASOURCE_NOT_FOUNT.getCode(),
                            "新配置连接测试失败，未修改数据源：" + testResult);
                }
            }
            dataSourceService.updateDataSource(dataSource);

            Map<String, Object> output = McpToolSupport.newResult();
            McpToolSupport.putIfNotNull(output, "datasourceId", id);
            McpToolSupport.putIfNotNull(output, "datasourceName", dataSource.getDatasourceName());
            McpToolSupport.putIfNotNull(output, "jdbcUrl", dataSource.getJdbcUrl());
            output.put("connectionTested", connectionChanged);
            return output;
        });
    }

    private BaseDatasource buildDatasource(Integer datasourceId, String datasourceName, String datasourceType,
                                           String host, String port, String databaseName, String username,
                                           String password, String remarks, String jdbcUrl) {
        String type = guard.requireSupportedType(support.requireText(datasourceType, "datasourceType"));
        String resolvedUrl = guard.validateJdbcUrl(type,
                support.buildJdbcUrl(type, host, port, databaseName, jdbcUrl));

        BaseDatasource dataSource = new BaseDatasource();
        dataSource.setDatasourceId(datasourceId);
        dataSource.setDatasourceName(datasourceName);
        dataSource.setDatasourceType(type);
        dataSource.setJdbcUrl(resolvedUrl);
        dataSource.setHost(host);
        dataSource.setPort(port);
        dataSource.setUsername(support.requireText(username, "username"));
        dataSource.setPassword(support.encodePassword(support.requireText(password, "password")));
        dataSource.setRemarks(remarks);
        return dataSource;
    }

    private BaseDatasource requireExisting(Integer datasourceId) {
        BaseDatasource dataSource = dataSourceService.getDataSource(datasourceId);
        if (dataSource == null) {
            throw new CustomException(ErrorStatusEnum.DATASOURCE_NOT_FOUNT.getCode(), "无效的数据源：" + datasourceId);
        }
        return dataSource;
    }

    private Map<String, Object> pageResult(PageInfo<BaseDatasource> page, int pageNum, int pageSize) {
        List<Map<String, Object>> list = new ArrayList<>();
        List<BaseDatasource> source = page == null ? null : page.getList();
        if (source != null) {
            for (BaseDatasource item : source) {
                list.add(toDatasource(item));
            }
        }
        Map<String, Object> result = McpToolSupport.newResult();
        result.put("total", page == null ? 0L : page.getTotal());
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        result.put("list", list);
        return result;
    }

    /**
     * 数据集输出裁剪：只保留定位所需字段，密码恒为脱敏值。
     */
    private Map<String, Object> toDatasource(BaseDatasource dataSource) {
        Map<String, Object> item = McpToolSupport.newResult();
        McpToolSupport.putIfNotNull(item, "datasourceId", dataSource.getDatasourceId());
        McpToolSupport.putIfNotNull(item, "datasourceName", dataSource.getDatasourceName());
        McpToolSupport.putIfNotNull(item, "datasourceType", dataSource.getDatasourceType());
        McpToolSupport.putIfNotNull(item, "jdbcUrl", dataSource.getJdbcUrl());
        McpToolSupport.putIfNotNull(item, "host", dataSource.getHost());
        McpToolSupport.putIfNotNull(item, "port", dataSource.getPort());
        McpToolSupport.putIfNotNull(item, "remarks", dataSource.getRemarks());
        item.put("password", McpToolSupport.MASK);
        return item;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
