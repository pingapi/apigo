package cn.crabc.core.app.mcp.support;

import cn.crabc.core.app.util.SQLUtil;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 试跑安全闸门。
 * <p>
 * 「AI 只能跑查询」这条红线必须由工具层承担：
 * {@code JdbcStatement.validateSqlSafety} 对多语句只记录 warn 并不拦截，
 * 它拦截的是 xp_cmdshell / into outfile 等危险模式与超长 SQL。
 *
 * @author yuqf
 */
public final class SqlPreviewGuard {

    private static final Logger log = LoggerFactory.getLogger(SqlPreviewGuard.class);

    /**
     * 试跑结果的默认行数上限。
     */
    public static final int DEFAULT_PREVIEW_LIMIT = 100;

    private static final int SELECT_ONLY_CODE = ErrorStatusEnum.API_SQL_ERROR.getCode();

    private static final String SELECT_ONLY_MESSAGE =
            "试跑仅允许 SELECT 查询语句；如需变更数据，请创建接口并交由人工确认后调用";

    /**
     * 已存在 LIMIT 的判定（数字、占位符、MyBatis 参数）。
     */
    private static final Pattern LIMIT_PATTERN =
            Pattern.compile("(?i)\\blimit\\s+(\\d+|\\?|#\\{|\\$\\{)");

    /**
     * 变更类关键字（仅在 WITH 开头的兜底判定中做扫描）。
     */
    private static final Pattern DML_PATTERN =
            Pattern.compile("(?i)\\b(insert|update|delete|drop|truncate|alter|create|grant|revoke|merge|call)\\b");

    /**
     * MyBatis 动态标签，出现时不做 Druid 解析（解析必然失败并打 ERROR 日志）。
     */
    private static final Pattern DYNAMIC_TAG_PATTERN =
            Pattern.compile("(?i)</?(script|if|foreach|where|set|choose|when|otherwise|trim)\\b");

    /**
     * 读取服务端本地文件或执行外部命令的函数（部分数据库支持在 SELECT 中调用）。
     * <p>
     * 例如 duckdb 的 {@code read_text/read_csv/glob}、H2 的 {@code CSVREAD}、
     * MySQL/PostgreSQL 的 {@code LOAD_FILE}/{@code pg_read_file}。
     * 这类函数即便写在 SELECT 里也能把服务器文件内容带回模型上下文，必须拦截。
     */
    private static final Pattern FILE_ACCESS_PATTERN = Pattern.compile(
            "(?i)\\b(read_text|read_blob|read_csv|read_csv_auto|read_json|read_json_auto|read_ndjson|"
                    + "read_parquet|sniff_csv|glob|csvread|load_file|pg_read_file|pg_read_binary_file|"
                    + "pg_ls_dir|lo_import|lo_export|xp_cmdshell|into\\s+outfile|into\\s+dumpfile|"
                    + "openrowset|opendatasource|dblink)\\s*\\(");

    private static final String FILE_ACCESS_MESSAGE =
            "试跑禁止调用读取服务端文件或执行外部命令的函数（如 read_text/CSVREAD/LOAD_FILE 等）";

    private SqlPreviewGuard() {
    }

    /**
     * 校验 SQL 是否为单条查询语句，不是则抛业务异常。
     *
     * @param sql            待校验 SQL
     * @param datasourceType 数据源类型
     */
    public static void validateSelectOnly(String sql, String datasourceType) {
        String script = sql == null ? "" : sql.trim();
        if (script.isEmpty()) {
            throw new CustomException(SELECT_ONLY_CODE, "SQL 不能为空");
        }

        // 1) 多语句拦截：使用项目自带的分句器（引号/注释/CDATA/MyBatis 标签感知）
        if (countStatements(script) > 1) {
            throw new CustomException(SELECT_ONLY_CODE, "试跑仅支持单条 SQL，请去掉多余的分号或拆分后分别试跑");
        }

        // 2) Druid 解析判定：命中 SELECT 即可放行（快速通道）
        //    注意两点：
        //    ① previewCheckSql 在「无 FROM/无表引用」的查询（如 select 1）上返回 false，
        //       这是解析器局限而非真的非查询，因此 false 只视为无法判定；
        //    ② 动态标签与 #{} 占位符会让 parseSqlTable 抛异常并打 ERROR 日志，
        //       因此这类 SQL 直接走关键字判定，避免日志噪音。
        if (!requiresKeywordFallback(script)) {
            try {
                if (SQLUtil.previewCheckSql(script, toDruidDbType(datasourceType))) {
                    return;
                }
            } catch (CustomException e) {
                if (e.getCode() == 55000) {
                    throw new CustomException(SELECT_ONLY_CODE, "试跑仅支持单条 SQL，请去掉多余的分号或拆分后分别试跑");
                }
                log.debug("Druid 无法解析试跑 SQL，转为关键字判定：{}", e.getMessage());
            } catch (Exception e) {
                log.debug("Druid 无法解析试跑 SQL，转为关键字判定：{}", e.getMessage());
            }
        }

        // 3) 关键字判定：动态 SQL 与 #{占位符} 的最终判定入口
        keywordFallback(script);
    }

    /**
     * 试跑/自测入口的完整闸门：单条 SELECT + 禁止服务端文件访问函数。
     * <p>
     * 与 {@link #validateSelectOnly} 的区别：后者只判断"是不是查询语句"，
     * 用于创建接口时判断是否需要 DML 二次确认；本方法用于真正执行 SQL 的入口。
     *
     * @param sql            待校验 SQL
     * @param datasourceType 数据源类型
     */
    public static void validatePreviewSql(String sql, String datasourceType) {
        validateSelectOnly(sql, datasourceType);
        assertNoFileAccessFunction(sql);
    }

    /**
     * 拒绝读取服务端文件/执行外部命令的函数调用。
     */
    public static void assertNoFileAccessFunction(String sql) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        // 先剥离注释，避免注释里的函数名造成误报
        String plain = stripTrailingComments(sql);
        Matcher matcher = FILE_ACCESS_PATTERN.matcher(plain);
        if (matcher.find()) {
            log.warn("试跑 SQL 命中危险函数：{}", matcher.group(1));
            throw new CustomException(SELECT_ONLY_CODE, FILE_ACCESS_MESSAGE + "：" + matcher.group(1));
        }
    }

    /**
     * 非抛异常版本的查询判定：供创建接口时决定是否要求显式 DML 确认。
     */
    public static boolean isSelectOnly(String sql, String datasourceType) {
        try {
            validateSelectOnly(sql, datasourceType);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 追加 LIMIT（已有 LIMIT 时不追加），并把末尾分号去掉。
     * <p>
     * 注意两个易错点：
     * <ul>
     *     <li>{@code <script>} 包裹的 SQL 需要插到闭合标签之前；</li>
     *     <li>末尾的行注释/块注释必须先剥离，否则 {@code select * from t -- x} 会变成
     *         {@code ... -- x LIMIT 100}，行数限制被注释吞掉。</li>
     * </ul>
     */
    public static String appendLimitIfAbsent(String sql, int limit) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        String body = stripTrailingComments(stripTrailingSemicolons(sql.trim()));
        if (body.isBlank()) {
            return sql;
        }
        if (LIMIT_PATTERN.matcher(body).find()) {
            return body;
        }
        int scriptEnd = body.toLowerCase(Locale.ROOT).lastIndexOf("</script>");
        if (scriptEnd >= 0) {
            return body.substring(0, scriptEnd).stripTrailing() + " LIMIT " + limit + " " + body.substring(scriptEnd);
        }
        return body + " LIMIT " + limit;
    }

    private static String stripTrailingSemicolons(String sql) {
        String current = sql.stripTrailing();
        while (current.endsWith(";")) {
            current = current.substring(0, current.length() - 1).stripTrailing();
        }
        return current;
    }

    /**
     * 剥离末尾的块注释与行注释，保证 LIMIT 不会被注释吞掉。
     */
    private static String stripTrailingComments(String sql) {
        String current = sql.stripTrailing();
        while (!current.isEmpty()) {
            if (current.endsWith("*/")) {
                int start = current.lastIndexOf("/*");
                if (start < 0) {
                    return current;
                }
                current = current.substring(0, start).stripTrailing();
                continue;
            }
            int lastNewline = current.lastIndexOf('\n');
            String lastLine = current.substring(lastNewline + 1);
            int commentIndex = lineCommentIndex(lastLine);
            if (commentIndex < 0) {
                return current;
            }
            current = current.substring(0, lastNewline + 1 + commentIndex).stripTrailing();
        }
        return current;
    }

    /**
     * 行内注释起始位置（忽略字符串字面量中的 -- 与 #），无注释返回 -1。
     */
    private static int lineCommentIndex(String line) {
        boolean singleQuote = false;
        boolean doubleQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !doubleQuote) {
                singleQuote = !singleQuote;
            } else if (c == '"' && !singleQuote) {
                doubleQuote = !doubleQuote;
            } else if (!singleQuote && !doubleQuote) {
                if (c == '-' && i + 1 < line.length() && line.charAt(i + 1) == '-') {
                    return i;
                }
                // MySQL 的 # 注释；注意排除 MyBatis 的 #{param} 占位符
                if (c == '#' && (i + 1 >= line.length() || line.charAt(i + 1) != '{')) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int countStatements(String script) {
        try {
            List<String> statements = SQLUtil.splitSqlStatements(script);
            return statements == null || statements.isEmpty() ? 1 : statements.size();
        } catch (Exception e) {
            log.debug("SQL 分句失败，跳过单语句校验：{}", e.getMessage());
            return 1;
        }
    }

    /**
     * 是否需要跳过 Druid 解析，直接走关键字判定。
     */
    private static boolean requiresKeywordFallback(String script) {
        if (script.contains("#{") || script.contains("${")) {
            return true;
        }
        return DYNAMIC_TAG_PATTERN.matcher(script).find();
    }

    private static void keywordFallback(String script) {
        String plain;
        try {
            plain = SQLUtil.sqlFilter(script);
        } catch (Exception e) {
            plain = script;
        }
        plain = plain.replaceAll("(?i)</?script>", " ").trim();
        if (plain.isEmpty()) {
            throw new CustomException(SELECT_ONLY_CODE,
                    "无法识别该 SQL 的语句类型（SQL 整体被动态标签包裹），请直接提供完整的 SELECT 语句");
        }
        String lower = plain.toLowerCase(Locale.ROOT);
        boolean startsWithSelect = lower.startsWith("select");
        boolean startsWithWith = lower.startsWith("with");
        if (!startsWithSelect && !startsWithWith) {
            throw new CustomException(SELECT_ONLY_CODE, SELECT_ONLY_MESSAGE);
        }
        // WITH 开头可能是 CTE + DELETE/UPDATE，做兜底扫描
        if (startsWithWith && DML_PATTERN.matcher(lower).find()) {
            throw new CustomException(SELECT_ONLY_CODE, SELECT_ONLY_MESSAGE);
        }
    }

    /**
     * 映射为 Druid 可识别的 dbType，未支持的方言统一按 mysql 处理（仅用于语句类型判定）。
     */
    private static String toDruidDbType(String datasourceType) {
        String type = datasourceType == null ? "" : datasourceType.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "oracle", "sqlserver", "clickhouse", "postgresql", "hive", "db2" -> type;
            case "opengauss" -> "postgresql";
            default -> "mysql";
        };
    }
}
