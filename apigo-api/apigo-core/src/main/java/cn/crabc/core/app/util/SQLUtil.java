package cn.crabc.core.app.util;


import cn.crabc.core.app.entity.BaseApiParam;
import cn.crabc.core.app.entity.vo.ColumnParseVo;
import cn.crabc.core.datasource.exception.CustomException;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.dialect.clickhouse.visitor.CKStatVisitor;
import com.alibaba.druid.sql.dialect.db2.visitor.DB2SchemaStatVisitor;
import com.alibaba.druid.sql.dialect.hive.visitor.HiveSchemaStatVisitor;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlSchemaStatVisitor;
import com.alibaba.druid.sql.dialect.oracle.visitor.OracleSchemaStatVisitor;
import com.alibaba.druid.sql.dialect.postgresql.visitor.PGSchemaStatVisitor;
import com.alibaba.druid.sql.dialect.sqlserver.visitor.SQLServerSchemaStatVisitor;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;
import com.alibaba.druid.stat.TableStat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL解析工具类
 *
 * @author yuqf
 */
public class SQLUtil {

    private final static Logger log = LoggerFactory.getLogger(SQLUtil.class);

    public final static String PARAM_PATTERN = "(?<=[#|$]\\{)(.+?)(?=\\})";

    /**
     * 多脚本执行只开放DML，避免查询结果集和变更结果混在同一返回语义里。
     */
    private static final Set<String> BATCH_DML_TYPE = new HashSet<>(Arrays.asList("insert", "update", "delete"));

    /**
     * 只识别项目支持的MyBatis动态标签，普通SQL小于号不参与XML深度判断。
     */
    private static final Set<String> MYBATIS_XML_TAGS = new HashSet<>(Arrays.asList(
            "script", "if", "foreach", "where", "set", "choose", "when", "otherwise", "trim"
    ));


    /**
     * 过滤SQL中的注释
     *
     * @param sql
     * @return
     */
    public static String sqlCommentReplace(String sql) {
        String SQL_COMMENT = "(?ms)('(?:''|[^'])*')|--.*?$|///*.*?//*/|\\/\\/[^\\n]*|\\/\\*([^\\*^\\/]*|[\\*^\\/*]*|[^\\**\\/]*)*\\*+\\/";
        Pattern p = Pattern.compile(SQL_COMMENT);
        return p.matcher(sql).replaceAll("#1");
    }

    /**
     * 检查SQL正确性时替换标签
     * @param sql
     * @return
     */
    public static String sqlFilter(String sql){
        String forRegex = "<foreach[\\s\\S]*?</foreach>";
        sql = sql.replaceAll(forRegex,"()");

        String regex = "<where>[\\s\\S]*?</where>|<if[\\s\\S]*?</if>|<set>[\\s\\S]*?</set>|<choose>[\\s\\S]*?</choose>|<when[\\s\\S]*?</when>";
        // 替换标签
        return sql.replaceAll(regex,"");
    }

    /**
     * 添加默认表名
     * @param sql
     * @param defaultTableName
     * @return
     */
    public static String checkTable(String sql, String defaultTableName) {
        Pattern pattern = Pattern.compile("(?i)\\bFROM\\b\\s+([\\s\\S]*?)\\bWHERE\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(sql);

        // 如果找到匹配项，则在匹配的内容中查找表名
        if (matcher.find()) {
            String fromToWhere = matcher.group(1).trim();
            // 如果没有表名，则在 FROM 关键字后面插入默认表名
            if (!fromToWhere.contains(" ")) {
                sql = sql.replaceFirst("(?i)\\bFROM\\b", "FROM " + defaultTableName);
            }
        }
        return sql;
    }

    /**
     * 解析查询字段
     *
     * @param sql
     * @param dbType
     * @return
     */
    public static Set<String> analyzeSQL(String sql, String dbType) {
        Set<String> aliasList = new HashSet<>();
        try {
            dbType = normalizeParseDialect(dbType);
            List<SQLStatement> sqlStatementList = SQLUtils.parseStatements(sql, DbType.valueOf(dbType), false);
            if (sqlStatementList == null || sqlStatementList.isEmpty()) {
                //throw new IllegalArgumentException("不是有效语句");
                return aliasList;
            }
            if (!(sqlStatementList.get(sqlStatementList.size() -1) instanceof SQLSelectStatement)) {
                return aliasList;
            }

            SQLSelectStatement selectStatement = (SQLSelectStatement) sqlStatementList.get(sqlStatementList.size() -1);

            SQLSelect sqlSelect = selectStatement.getSelect();

            SQLSelectQueryBlock firstQueryBlock = sqlSelect.getFirstQueryBlock();
            //normalized

            for (int i = 0; i < firstQueryBlock.getSelectList().size(); i++) {
                SQLSelectItem item = firstQueryBlock.getSelectList().get(i);

                SQLExpr expr = item.getExpr();
                String normalizedName = null;


                if (expr instanceof SQLAllColumnExpr) {
                    normalizedName = "*";
                } else if (expr instanceof SQLPropertyExpr && ((SQLPropertyExpr) expr).getName().equals("*")) {
                    normalizedName = "*";
                } else if (expr instanceof SQLIdentifierExpr) {
                    //标识
                    normalizedName = ((SQLIdentifierExpr) expr).normalizedName();//normalizedName能够去掉引号关键字
                } else if (expr instanceof SQLPropertyExpr) {
                    //带owner的标识
                    normalizedName = SQLUtils.normalize(((SQLPropertyExpr) expr).getName(), DbType.valueOf(dbType));//normalizedName能够去掉引号关键字，去掉owner
                } else {
                    //自动生成虚拟别名
                    normalizedName = "_col" + i;
                }
                String alias = item.computeAlias();
                if (alias == null) {
                    aliasList.add(normalizedName + "," + normalizedName);
                } else {
                    aliasList.add(normalizedName + ',' + alias);
                }
            }

            return aliasList;

        } catch (Exception e) {
            log.info("SQL解析失败：{}", e.getMessage());
            return aliasList;
        }
    }

    /**
     * 归一到 Druid 可识别的解析方言。
     * <p>
     * Druid 的 DbType 枚举不含 opengauss/duckdb/tdsql/dolphindb/custom，直接 valueOf
     * 会抛 IllegalArgumentException。这里只做语法解析的归一，不影响 SQL 实际执行。
     */
    private static String normalizeParseDialect(String dbType) {
        if (dbType == null) {
            return null;
        }
        String type = dbType.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "doris", "starrocks", "tdsql", "dolphindb", "custom" -> "mysql";
            case "opengauss", "duckdb" -> "postgresql";
            default -> type;
        };
    }

    /**
     * 解析SQL
     *
     * @param sql
     * @param dbType
     * @return
     */
    public static Map<String, Object> parseSqlTable(String sql, String dbType) {
        Map<String, Object> map = new HashMap<>();
        Set<String> tableName = new HashSet<>();
        List<String> operateName = new ArrayList<>();
        try {
            dbType = normalizeParseDialect(dbType);
            //格式化
            String sqlFormat = SQLUtils.format(sql, dbType);
            List<SQLStatement> stmtList = SQLUtils.parseStatements(sqlFormat, dbType);
            if (stmtList == null || stmtList.size() == 0) {
                return map;
            }
            if (stmtList.size() > 1) {
                throw new CustomException(55000, "不支持多条SQL语句，请删除多余的SQL");
            }
            // 只取一条执行语句解析
            SQLStatement sqlStatement = stmtList.get(0);
            SchemaStatVisitor visitor = null;
            switch (dbType.toLowerCase()) {
                case "oracle":
                    visitor = new OracleSchemaStatVisitor();
                    break;
                case "dm":
                    // DM 为 Oracle 兼容语法，复用 Oracle 解析器
                    visitor = new OracleSchemaStatVisitor();
                    break;
                case "postgresql":
                    visitor = new PGSchemaStatVisitor();
                    break;
                case "sqlserver":
                    visitor = new SQLServerSchemaStatVisitor();
                    break;
                case "db2":
                    visitor = new DB2SchemaStatVisitor();
                    break;
                case "clickhouse":
                    visitor = new CKStatVisitor();
                    break;
                case "hive":
                    visitor = new HiveSchemaStatVisitor();
                    break;
                default:
                    visitor = new MySqlSchemaStatVisitor();
            }
            sqlStatement.accept(visitor);
            Map<TableStat.Name, TableStat> tables = visitor.getTables();
            for (Map.Entry<TableStat.Name, TableStat> table : tables.entrySet()) {
                tableName.add(table.getKey().getName());
                operateName.add(table.getValue().toString());
            }

        } catch (Exception e) {
            log.error("解析SQL异常", e);
            throw new CustomException(55001, "SQL解析失败，如果SQL中包含特殊字符,请使用双引号括起来");
        }

        map.put("tableName", tableName);
        map.put("operateName", operateName);
        return map;
    }

    /**
     * 正则解析请求参数
     *
     * @param sql
     * @return
     */
    public static Set<String> parseParams(String sql) {
        Set<String> columns = new HashSet<>();
        try {
            sql = sqlCommentReplace(sql);
            Pattern pattern = Pattern.compile(PARAM_PATTERN);
            Matcher m = pattern.matcher(sql);
            while (m.find()) {
                columns.add(m.group(0));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return columns;
    }

    /**
     * 按脚本级分号切分SQL，字符串、注释、MyBatis动态标签和XML实体中的分号不作为脚本边界。
     *
     * @param sql 原始SQL脚本
     * @return 可执行SQL片段
     */
    public static List<String> splitSqlStatements(String sql) {
        List<String> statements = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            return statements;
        }

        StringBuilder current = new StringBuilder();
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean lineComment = false;
        boolean blockComment = false;
        int xmlDepth = 0;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (lineComment) {
                current.append(c);
                if (c == '\n' || c == '\r') {
                    lineComment = false;
                }
                continue;
            }

            if (blockComment) {
                current.append(c);
                if (c == '*' && next == '/') {
                    current.append(next);
                    i++;
                    blockComment = false;
                }
                continue;
            }

            if (singleQuote) {
                current.append(c);
                if (c == '\'' && next == '\'') {
                    current.append(next);
                    i++;
                } else if (c == '\'' && !isBackslashEscaped(sql, i)) {
                    singleQuote = false;
                }
                continue;
            }

            if (doubleQuote) {
                current.append(c);
                if (c == '"' && next == '"') {
                    current.append(next);
                    i++;
                } else if (c == '"' && !isBackslashEscaped(sql, i)) {
                    doubleQuote = false;
                }
                continue;
            }

            if (c == '-' && next == '-') {
                current.append(c).append(next);
                i++;
                lineComment = true;
                continue;
            }
            if (c == '/' && next == '/') {
                current.append(c).append(next);
                i++;
                lineComment = true;
                continue;
            }
            if (c == '/' && next == '*') {
                current.append(c).append(next);
                i++;
                blockComment = true;
                continue;
            }
            if (c == '\'') {
                current.append(c);
                singleQuote = true;
                continue;
            }
            if (c == '"') {
                current.append(c);
                doubleQuote = true;
                continue;
            }
            if (sql.startsWith("<![CDATA[", i)) {
                int cdataEnd = sql.indexOf("]]>", i + 9);
                int end = cdataEnd < 0 ? sql.length() - 1 : cdataEnd + 2;
                current.append(sql, i, end + 1);
                i = end;
                continue;
            }
            if (c == '<') {
                XmlTag xmlTag = parseMyBatisTag(sql, i);
                if (xmlTag != null) {
                    current.append(sql, i, xmlTag.endIndex + 1);
                    if (xmlTag.closing) {
                        xmlDepth = Math.max(0, xmlDepth - 1);
                    } else if (!xmlTag.selfClosing) {
                        xmlDepth++;
                    }
                    i = xmlTag.endIndex;
                    continue;
                }
            }
            if (c == ';' && xmlDepth == 0 && !isXmlEntitySemicolon(sql, i)) {
                addSqlStatement(statements, current);
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        addSqlStatement(statements, current);
        return statements;
    }

    /**
     * 校验多脚本只包含insert/update/delete，避免多结果集和纯查询脚本进入批量DML执行链路。
     *
     * @param statements 已切分的SQL脚本
     */
    public static void validateBatchDmlStatements(List<String> statements) {
        if (statements == null || statements.size() <= 1) {
            return;
        }
        for (String statement : statements) {
            String sqlType = getOperateType(statement);
            if (!BATCH_DML_TYPE.contains(sqlType)) {
                throw new CustomException(40011, "多脚本只支持insert、update、delete类型SQL");
            }
        }
    }

    private static final List<String> DML_TYPE = Arrays.asList("select", "insert", "update", "delete");

    /**
     * 校验SQL的合法性
     *
     * @param sql
     * @param dbType
     * @return
     */
    public static boolean checkSql(String sql, String dbType) {
        Map<String, Object> map = parseSqlTable(sql, dbType);
        if (map.containsKey("operateName")) {
            List<String> operate = (List<String>) map.get("operateName");
            if (operate == null || operate.size() == 0) {
                return false;
            }
            String type = operate.get(0);
            if (("elasticsearch".equals(dbType) || "mongodb".equals(dbType)) &&
                    ("update".equalsIgnoreCase(type) || "delete".equalsIgnoreCase(type))) {
                return false;
            }
            return DML_TYPE.contains(type.toLowerCase());
        }
        return false;
    }

    /**
     * 校验SQL的合法性
     *
     * @param sql
     * @param dbType
     * @return
     */
    public static boolean previewCheckSql(String sql, String dbType) {
        Map<String, Object> map = parseSqlTable(sql, dbType);
        if (map.containsKey("operateName")) {
            List<String> operate = (List<String>) map.get("operateName");
            if (operate == null || operate.size() == 0) {
                return false;
            }
            String type = operate.get(0);
            return "select".equalsIgnoreCase(type);
        }
        return false;
    }

    /**
     * 校验并返回类型
     *
     * @param sql
     * @param dbType
     * @return
     */
    public static String getSqlType(String sql, String dbType) {
        Map<String, Object> map = parseSqlTable(sql, dbType);
        if (map.containsKey("operateName")) {
            List<String> operate = (List<String>) map.get("operateName");
            if (operate == null || operate.size() == 0) {
                return null;
            }
            String type = operate.get(0);
            if (("elasticsearch".equals(dbType) || "mongodb".equals(dbType)) &&
                    ("update".equalsIgnoreCase(type) || "delete".equalsIgnoreCase(type))) {
                return null;
            }
            if (DML_TYPE.contains(type.toLowerCase())) {
                return type;
            }
        }
        return null;
    }

    /**
     * 过滤SQL中的参数
     * @param sql
     * @param reqParams
     * @param paramsConfig
     * @return
     */
    public static String filterParams(String sql, Object reqParams, List<BaseApiParam> paramsConfig) {
        if (paramsConfig == null || paramsConfig.size() == 0) {
            // 没有查询请求参数设置，清空当前的请求参数
            return sql;
        }
        // TODO 暂不支持数组集合
        if (reqParams instanceof Map) {
            Map<String, Object> paramMap = (Map<String, Object>) reqParams;
            for (BaseApiParam param : paramsConfig) {
                if ("Y".equals(param.getRequired()) && !paramMap.containsKey(param.getParamName())) {
                    // 必传参数没有传，则报错提示
                    throw new CustomException(52005, "请求参数缺少！");
                } else if ("N".equals(param.getRequired()) && !paramMap.containsKey(param.getParamName())) {
                    // 非比传参数，去掉SQL中相应的条件
                    sql = regexSql(sql, param.getParamName());
                }
            }
        }
        return sql;
    }

    /**
     * 正则过滤请求参数
     *
     * @param sql
     * @param paramName
     * @return
     */
    public static String regexSql(String sql, String paramName) {
        String group = "(?i)group.*?(?i)by.*?" + paramName + "}";
        String and = "(?i)and.*?" + paramName + "}";
        String where = "(?i)where.*?" + paramName + "}";
        String orderDesc = "(?i)order.*?(?i)by.*?" + paramName + "}.*?(?i)sc";
        String order = "(?i)order.*?(?i)by.*?" + paramName + "}";
        if (Pattern.compile(orderDesc).matcher(sql).find()) {
            sql = sql.replaceAll(orderDesc, "");
        } else if (Pattern.compile(order).matcher(sql).find()) {
            sql = sql.replaceAll(order, "");
        } else if (Pattern.compile(group).matcher(sql).find()) {
            sql = sql.replaceAll(group, "");
        } else if (Pattern.compile(and).matcher(sql).find()) {
            sql = sql.replaceAll(and, "");
        } else if (Pattern.compile(where).matcher(sql).find()) {
            sql = sql.replaceAll(where, "where 1=1");
        }
        return sql;
    }
    /**
     * 获取sql操作类型
     * @param sql
     * @return
     */
    public static String getOperateType(String sql){
        sql = trimLeadingSqlComment(sql);
        String[] sqls = sql.trim().split("\\s+");
        return sqls.length > 1 ? sqls[0].toLowerCase().trim() : "select";
    }

    /**
     * 收尾当前脚本片段，空片段来自连续分号或末尾分号，不能进入执行链路。
     */
    private static void addSqlStatement(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
    }

    /**
     * 判断引号是否被反斜杠转义，避免字符串里的分号提前结束脚本。
     */
    private static boolean isBackslashEscaped(String sql, int index) {
        int slashCount = 0;
        for (int i = index - 1; i >= 0 && sql.charAt(i) == '\\'; i--) {
            slashCount++;
        }
        return slashCount % 2 == 1;
    }

    /**
     * XML实体自身以分号结束，不能把 &gt;、&lt;= 等动态SQL片段拆成多脚本。
     */
    private static boolean isXmlEntitySemicolon(String sql, int semicolonIndex) {
        int start = Math.max(0, semicolonIndex - 16);
        int ampIndex = sql.lastIndexOf('&', semicolonIndex);
        if (ampIndex < start) {
            return false;
        }
        String entity = sql.substring(ampIndex, semicolonIndex + 1);
        return entity.matches("&(#\\d+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]+);");
    }

    /**
     * 只识别MyBatis动态SQL标签，普通SQL里的小于号不进入XML深度计算。
     */
    private static XmlTag parseMyBatisTag(String sql, int startIndex) {
        int endIndex = findXmlTagEnd(sql, startIndex);
        if (endIndex < 0) {
            return null;
        }
        String tagText = sql.substring(startIndex, endIndex + 1);
        String tagName = getXmlTagName(tagText);
        if (!MYBATIS_XML_TAGS.contains(tagName)) {
            return null;
        }
        boolean closing = tagText.startsWith("</");
        boolean selfClosing = tagText.endsWith("/>");
        return new XmlTag(endIndex, closing, selfClosing);
    }

    /**
     * 查找标签右边界时跳过属性字符串，避免 test="a &gt; b" 里的符号误判。
     */
    private static int findXmlTagEnd(String sql, int startIndex) {
        boolean singleQuote = false;
        boolean doubleQuote = false;
        for (int i = startIndex + 1; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (singleQuote) {
                if (c == '\'' && !isBackslashEscaped(sql, i)) {
                    singleQuote = false;
                }
                continue;
            }
            if (doubleQuote) {
                if (c == '"' && !isBackslashEscaped(sql, i)) {
                    doubleQuote = false;
                }
                continue;
            }
            if (c == '\'') {
                singleQuote = true;
            } else if (c == '"') {
                doubleQuote = true;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    /**
     * 提取标签名用于白名单判断，控制脚本切分只关注项目支持的动态SQL标签。
     */
    private static String getXmlTagName(String tagText) {
        int index = tagText.startsWith("</") ? 2 : 1;
        while (index < tagText.length() && Character.isWhitespace(tagText.charAt(index))) {
            index++;
        }
        int start = index;
        while (index < tagText.length() && Character.isLetter(tagText.charAt(index))) {
            index++;
        }
        return tagText.substring(start, index).toLowerCase();
    }

    /**
     * 判断SQL类型前先移除头部注释，避免注释开头的DML被识别成查询。
     */
    private static String trimLeadingSqlComment(String sql) {
        if (sql == null) {
            return "";
        }
        String result = sql.trim();
        boolean removed;
        do {
            removed = false;
            if (result.startsWith("--") || result.startsWith("//")) {
                int lineEnd = result.indexOf('\n');
                result = lineEnd < 0 ? "" : result.substring(lineEnd + 1).trim();
                removed = true;
            } else if (result.startsWith("/*")) {
                int blockEnd = result.indexOf("*/");
                result = blockEnd < 0 ? "" : result.substring(blockEnd + 2).trim();
                removed = true;
            }
        } while (removed);
        return result;
    }

    /**
     * MyBatis标签扫描结果，只保存切分所需的右边界和标签类型。
     */
    private static class XmlTag {
        private final int endIndex;
        private final boolean closing;
        private final boolean selfClosing;

        /**
         * 标签对象只在切分过程中使用，避免把XML解析状态泄漏到执行层。
         */
        private XmlTag(int endIndex, boolean closing, boolean selfClosing) {
            this.endIndex = endIndex;
            this.closing = closing;
            this.selfClosing = selfClosing;
        }
    }

    /**
     * 解析jdbc url里面的类型
     * @param jdbcUrl
     * @return
     */
    public static String getDataSourceType(String jdbcUrl) {
        try {
            URI uri = new URI(jdbcUrl.replace("jdbc:", ""));
            // 获取数据库类型
            return uri.getScheme();
        }catch (Exception e) {
        }
        return "custom";
    }

    /**
     * 字符串下划线转驼峰
     * @param param
     * @return
     */
    public static String underlineToCamel(String param){
        if (param==null||"".equals(param.trim())){
            return "";
        }
        int len=param.length();
        StringBuilder sb=new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = Character.toLowerCase(param.charAt(i));
            if (c == '_'){
                if (++i<len){
                    sb.append(Character.toUpperCase(param.charAt(i)));
                }
            }else{
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static Set<String> extractForeachParams(String sql) {
        Set<String> params = new HashSet<>();
        Pattern pattern = Pattern.compile("collection='(.*?)'");
        Matcher matcher = pattern.matcher(sql);
        while (matcher.find()) {
            params.add(matcher.group(1));
        }
        return params;
    }

    public static Set<String> extractIfParams(String sql) {
        Set<String> params = new HashSet<>();
        Pattern pattern = Pattern.compile(" test=['\"](\\w+)\\s*(?:!=|>=|<=|==|<|>|&lt;|&lt;=|&gt;|&gt;=)");
        Matcher matcher = pattern.matcher(sql);
        while (matcher.find()) {
            params.add(matcher.group(1));
        }
        return params;
    }

    public static String completeSql(String sql) {
        sql = sql.trim().toLowerCase();
        if (sql.endsWith("from")) {
            return sql + " test";
        } else if (sql.endsWith("where")) {
            return sql + " 1=1";
        }
        return sql;
    }

    public static Set<String> parseResultColumns(String sql, String datasourceType) {
        Set<String> resNames = SQLUtil.analyzeSQL(sql.trim(), datasourceType);
        if (resNames.isEmpty()) {
            sql = SQLUtil.checkTable(sql, " test ");
            resNames = SQLUtil.analyzeSQL(sql.trim(), datasourceType);
        }
        return resNames;
    }

    public static ColumnParseVo buildColumnInfo(String name) {
        ColumnParseVo column = new ColumnParseVo();
        column.setColName(name);
        column.setColType("String");
        column.setItemIndex(0);
        return column;
    }
}
