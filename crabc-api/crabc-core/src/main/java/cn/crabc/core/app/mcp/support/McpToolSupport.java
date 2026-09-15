package cn.crabc.core.app.mcp.support;

import cn.crabc.core.app.mcp.auth.McpAuthContext;
import cn.crabc.core.app.mcp.auth.McpTokenScope;
import cn.crabc.core.app.util.UserThreadLocal;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * MCP 工具层通用支撑。
 * <p>
 * 职责：用户上下文绑定、分页钳制、文本截断、参数校验、数据源密码编码与 JDBC URL 拼装。
 * 不承载任何业务逻辑（业务逻辑仍由现有 service 提供）。
 *
 * @author yuqf
 */
@Component
public class McpToolSupport {

    /**
     * 列表类工具返回的脱敏占位符。
     */
    public static final String MASK = "******";

    private static final int DEFAULT_PAGE_SIZE = 10;

    @Value("${crabc.mcp.user-id:1}")
    private String defaultUserId;

    @Value("${crabc.mcp.max-page-size:20}")
    private int maxPageSize;

    @Value("${crabc.mcp.max-text-length:2000}")
    private int maxTextLength;

    private final McpAuditRecorder auditRecorder;

    public McpToolSupport(McpAuditRecorder auditRecorder) {
        this.auditRecorder = auditRecorder;
    }

    /**
     * 绑定用户上下文并记录审计。
     * <p>
     * 所有 {@code @McpTool} 的业务体必须在本包装内执行：工具方法与 service 调用
     * 在同一线程，保证 {@code UserThreadLocal.getUserId()} 可见（createBy/updateBy 有主）。
     *
     * @param toolName 工具名
     * @param action   业务体
     */
    public <T> T bindUser(String toolName, Supplier<T> action) {
        return bindUser(toolName, null, action);
    }

    /**
     * 绑定用户上下文并记录审计（带入参摘要，便于变更类操作事后追溯）。
     *
     * @param toolName    工具名
     * @param argsSummary 入参摘要（仅记录标识类字段，禁止包含密码等敏感信息）
     * @param action      业务体
     */
    public <T> T bindUser(String toolName, String argsSummary, Supplier<T> action) {
        String userId = this.userId();
        Map<String, Object> user = new LinkedHashMap<>(4);
        user.put("userId", userId);
        user.put("userName", "mcp:" + McpAuthContext.tokenName());
        UserThreadLocal.set(user);

        long start = System.currentTimeMillis();
        try {
            T result = action.get();
            auditRecorder.record(toolName, argsSummary, true, null, System.currentTimeMillis() - start);
            return result;
        } catch (RuntimeException e) {
            auditRecorder.record(toolName, argsSummary, false, e.getMessage(), System.currentTimeMillis() - start);
            throw e;
        } finally {
            // 虚拟线程会被复用，必须清理防串号
            UserThreadLocal.remove();
        }
    }

    /**
     * 变更类工具的包装：先校验令牌作用域，再绑定用户上下文。
     * <p>
     * 只有 {@code readwrite} 令牌才能执行变更；未检测到鉴权上下文时按只读处理（fail-closed）。
     *
     * @param toolName    工具名
     * @param argsSummary 入参摘要
     * @param action      业务体
     */
    public <T> T bindWrite(String toolName, String argsSummary, Supplier<T> action) {
        requireWrite(toolName);
        return bindUser(toolName, argsSummary, action);
    }

    /**
     * 校验当前令牌是否具备写权限，不满足直接抛业务异常（回传模型自纠）。
     */
    public void requireWrite(String toolName) {
        McpTokenScope scope = McpAuthContext.scope();
        if (scope == McpTokenScope.READ_WRITE) {
            return;
        }
        String message = scope == McpTokenScope.READ_ONLY
                ? "当前 MCP 令牌为只读（scope=readonly），不允许调用变更类工具：" + toolName + "。如需执行请改用读写令牌"
                : "未检测到 MCP 鉴权上下文（可能不是经 /mcp 端点发起的调用），出于安全考虑已按只读处理：" + toolName;
        throw new CustomException(ErrorStatusEnum.FORBID_OPERATE.getCode(), message);
    }

    /**
     * 高危操作的二次确认闸门。
     * <p>
     * 仅靠工具 description 提示模型"先复述确认"并不可靠，这里要求调用方显式传
     * {@code confirm=true}，未确认直接拒绝并要求重新调用。
     *
     * @param confirm 调用方传入的确认标记
     * @param reason  需要确认的原因（回传模型，说明后果）
     */
    public static void requireConfirmed(Boolean confirm, String reason) {
        if (!Boolean.TRUE.equals(confirm)) {
            throw new CustomException(ErrorStatusEnum.FORBID_OPERATE.getCode(),
                    "未确认执行：" + reason + "。请先向用户复述影响范围并取得确认，再以 confirm=true 重新调用");
        }
    }

    /**
     * MCP 操作归属用户，优先取配置，缺省为 admin（userId=1）。
     */
    public String userId() {
        if (defaultUserId == null || defaultUserId.isBlank()) {
            return "1";
        }
        return defaultUserId.trim();
    }

    /**
     * 分页页码钳制。
     */
    public int pageNum(Integer value) {
        return value == null || value < 1 ? 1 : value;
    }

    /**
     * 分页大小钳制：默认 10，上限由 crabc.mcp.max-page-size 控制，防止上下文爆炸。
     */
    public int pageSize(Integer value) {
        int size = value == null || value < 1 ? DEFAULT_PAGE_SIZE : value;
        int limit = maxPageSize < 1 ? 20 : maxPageSize;
        return Math.min(size, limit);
    }

    /**
     * 按配置的 max-text-length 截断长文本（SQL 脚本等）。
     */
    public String truncateText(String text) {
        return truncate(text, maxTextLength);
    }

    public String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        if (max <= 0 || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...(已截断，原文 " + text.length() + " 字符)";
    }

    /**
     * 必填文本校验。
     */
    public String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), "必传参数不能为空：" + name);
        }
        return value.trim();
    }

    /**
     * 必填数值校验。
     */
    public Integer requireInteger(String value, String name) {
        String text = requireText(value, name);
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), name + " 必须为数字：" + text);
        }
    }

    /**
     * 数据源密码编码。
     * <p>
     * 平台约定：调用方传入 Base64；{@code BaseDataSourceServiceImpl.addCache} 与
     * {@code test} 均会做 Base64 解码，因此工具层必须先编码再入库。
     */
    public String encodePassword(String plainPassword) {
        if (plainPassword == null) {
            return "";
        }
        return Base64.getEncoder().encodeToString(plainPassword.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 按数据源类型拼装 JDBC URL；入参已给出 jdbcUrl 时直接使用。
     * <p>
     * 平台只认 {@code jdbcUrl}（{@code DefaultDataSourceDriver} 直接 setJdbcUrl），
     * 不做 host/port 拼装，因此这里由工具层承担，方便 AI 只提供 host/port/库名。
     */
    public String buildJdbcUrl(String datasourceType, String host, String port, String databaseName, String jdbcUrl) {
        if (jdbcUrl != null && !jdbcUrl.isBlank()) {
            return jdbcUrl.trim();
        }
        if (host == null || host.isBlank()) {
            throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(),
                    "必传参数不能为空：host（或直接提供完整的 jdbcUrl）");
        }
        String type = datasourceType == null ? "" : datasourceType.trim().toLowerCase(Locale.ROOT);
        String address = host.trim();
        String db = databaseName == null ? "" : databaseName.trim();
        String resolvedPort = port == null || port.isBlank() ? defaultPort(type) : port.trim();

        return switch (type) {
            case "mysql", "tidb", "starrocks" ->
                    "jdbc:mysql://" + address + ":" + resolvedPort + "/" + db
                            + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
            case "doris", "mariadb", "tdsql" -> "jdbc:mysql://" + address + ":" + resolvedPort + "/" + db;
            case "postgresql", "opengauss" -> "jdbc:postgresql://" + address + ":" + resolvedPort + "/" + db;
            case "oracle" -> "jdbc:oracle:thin:@" + address + ":" + resolvedPort + ":" + db;
            case "sqlserver" -> "jdbc:sqlserver://" + address + ":" + resolvedPort + ";databaseName=" + db;
            case "dm" -> "jdbc:dm://" + address + ":" + resolvedPort + "/" + db;
            case "clickhouse" -> "jdbc:clickhouse://" + address + ":" + resolvedPort + "/" + db;
            case "dolphindb" -> "jdbc:dolphindb://" + address + ":" + resolvedPort + "?databasePath=" + db;
            default -> throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(),
                    "数据源类型 " + datasourceType + " 无法自动拼装 JDBC URL，请直接提供 jdbcUrl 参数"
                            + "（如 oracle/duckdb/custom 等类型需要完整连接串）");
        };
    }

    private String defaultPort(String type) {
        return switch (type) {
            case "mysql", "tidb", "starrocks" -> "3306";
            case "doris" -> "9030";
            case "postgresql", "opengauss" -> "5432";
            case "sqlserver" -> "1433";
            case "oracle" -> "1521";
            case "dm" -> "5236";
            case "clickhouse" -> "8123";
            case "dolphindb" -> "8848";
            default -> "3306";
        };
    }

    /**
     * 结果 Map 构造与轻量写入（null 不写入，避免模型上下文出现无意义字段）。
     */
    public static Map<String, Object> newResult() {
        return new LinkedHashMap<>();
    }

    public static Map<String, Object> result(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>(4);
        putIfNotNull(map, key, value);
        return map;
    }

    public static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /**
     * 去掉最外层 {@code <script>} / {@code </script>} 标签。
     * <p>
     * AI 习惯用 MyBatis 的 script 根标签包裹动态 SQL，而平台的解析链路
     * （{@code SQLUtil.sqlFilter} / Druid 解析）不支持该标签，执行层也会按需自行包装，
     * 因此工具层统一剥离，避免解析失败或重复包装。
     */
    public static String stripScriptTags(String sql) {
        if (sql == null) {
            return null;
        }
        String result = sql.trim();
        if (result.length() >= 8 && result.regionMatches(true, 0, "<script>", 0, 8)) {
            result = result.substring(8).trim();
        }
        if (result.length() >= 9 && result.regionMatches(true, result.length() - 9, "</script>", 0, 9)) {
            result = result.substring(0, result.length() - 9).trim();
        }
        return result;
    }

    /**
     * 查询结果裁剪：限制行数、列数与单值长度，避免模型上下文膨胀。
     * <p>
     * 入参为执行层返回的原始对象：结果集为 {@code List<Map>}，DML 为受影响行数。
     */
    public static Map<String, Object> trimQueryResult(Object result, int maxRows, int maxColumns, int maxValueLength) {
        Map<String, Object> output = newResult();
        if (!(result instanceof List<?> list)) {
            output.put("affectedRows", result == null ? 0 : result);
            output.put("tip", "该语句不是查询语句，未返回结果集");
            return output;
        }
        output.put("rowCount", list.size());
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> columns = new ArrayList<>();
        int limit = Math.min(list.size(), Math.max(maxRows, 0));
        for (int i = 0; i < limit; i++) {
            Object row = list.get(i);
            if (!(row instanceof Map<?, ?> rowMap)) {
                continue;
            }
            Map<String, Object> trimmed = new LinkedHashMap<>();
            int columnIndex = 0;
            for (Map.Entry<?, ?> entry : rowMap.entrySet()) {
                if (columnIndex++ >= maxColumns) {
                    break;
                }
                String key = String.valueOf(entry.getKey());
                if (i == 0) {
                    columns.add(key);
                }
                Object value = entry.getValue();
                trimmed.put(key, trimValue(value, maxValueLength));
            }
            rows.add(trimmed);
        }
        output.put("columns", columns);
        output.put("rows", rows);
        if (list.size() > limit) {
            output.put("truncated", true);
            output.put("tip", "仅返回前 " + limit + " 行，实际返回 " + list.size() + " 行");
        }
        return output;
    }

    /**
     * 单值裁剪。
     * <p>
     * 数字与布尔原样保留（避免模型把 {@code 128} 误读成字符串 "128"），
     * 字符串超长时才截断，二进制与复杂对象转为可读文本。
     */
    static Object trimValue(Object value, int max) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return "<binary " + bytes.length + " bytes>";
        }
        if (value instanceof String text) {
            return truncateValue(text, max);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof CharSequence sequence) {
            return truncateValue(sequence.toString(), max);
        }
        return truncateValue(String.valueOf(value), max);
    }

    private static String truncateValue(String value, int max) {
        if (max <= 0 || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...(已截断)";
    }

    /**
     * 解析 {@code paramsJson} 入参（SQL 占位符取值）。
     * <p>
     * 由多个工具共用，避免各工具重复实现导致报错语义不一致。
     *
     * @param jsonMapper 容器内的 JsonMapper
     * @param paramsJson JSON 对象字符串
     * @param paramName  参数字段名（用于错误提示）
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseParamsJson(JsonMapper jsonMapper, String paramsJson, String paramName) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> parsed = jsonMapper.readValue(paramsJson, HashMap.class);
            return parsed == null ? new HashMap<>() : parsed;
        } catch (Exception e) {
            throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(),
                    paramName + " 不是合法的 JSON 对象：" + e.getMessage());
        }
    }
}
