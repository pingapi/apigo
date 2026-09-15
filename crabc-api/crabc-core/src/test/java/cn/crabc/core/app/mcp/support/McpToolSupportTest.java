package cn.crabc.core.app.mcp.support;

import cn.crabc.core.app.mcp.McpTestFixture;
import cn.crabc.core.app.mcp.auth.McpAuthContext;
import cn.crabc.core.app.util.UserThreadLocal;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 工具层通用支撑测试。
 *
 * @author yuqf
 */
class McpToolSupportTest {

    private final McpToolSupport support = McpTestFixture.support();

    @AfterEach
    void tearDown() {
        McpTestFixture.clearContext();
    }

    @Test
    @DisplayName("分页钳制：页码最小 1，页大小默认 10、上限 20")
    void pageClamp() {
        assertEquals(1, support.pageNum(null));
        assertEquals(1, support.pageNum(0));
        assertEquals(1, support.pageNum(-5));
        assertEquals(3, support.pageNum(3));

        assertEquals(10, support.pageSize(null));
        assertEquals(10, support.pageSize(0));
        assertEquals(5, support.pageSize(5));
        assertEquals(20, support.pageSize(100), "超过上限必须被钳制，防止上下文爆炸");
    }

    @Test
    @DisplayName("文本截断：空值透传、短文本不变、长文本带截断标记")
    void truncate() {
        assertNull(support.truncate(null, 10));
        assertEquals("abc", support.truncate("abc", 10));
        String longText = "x".repeat(2100);
        String truncated = support.truncateText(longText);
        assertTrue(truncated.startsWith("x".repeat(2000)));
        assertTrue(truncated.contains("已截断"));
    }

    @Test
    @DisplayName("必填参数校验：空值抛业务异常，正常值去空格")
    void requireText() {
        CustomException ex = assertThrows(CustomException.class, () -> support.requireText("  ", "datasourceId"));
        assertEquals(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), ex.getCode());
        assertTrue(ex.getMsg().contains("datasourceId"));

        assertEquals("3", support.requireText("  3 ", "datasourceId"));
    }

    @Test
    @DisplayName("数字参数校验：非数字抛业务异常")
    void requireInteger() {
        assertEquals(3, support.requireInteger("3", "datasourceId"));
        CustomException ex = assertThrows(CustomException.class, () -> support.requireInteger("abc", "datasourceId"));
        assertEquals(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("数据源密码必须 Base64 编码（服务层会 Base64 解码）")
    void encodePassword() {
        String encoded = support.encodePassword("Read@123");
        assertEquals("Read@123", new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
        assertEquals("", support.encodePassword(null));
    }

    @Test
    @DisplayName("JDBC URL 拼装：按类型使用默认端口，显式 jdbcUrl 优先")
    void buildJdbcUrl() {
        String mysql = support.buildJdbcUrl("mysql", "192.168.1.10", null, "orders", null);
        assertTrue(mysql.startsWith("jdbc:mysql://192.168.1.10:3306/orders"), mysql);
        assertTrue(mysql.contains("useSSL=false"), mysql);

        String mysqlWithPort = support.buildJdbcUrl("mysql", "127.0.0.1", "3307", "crm", null);
        assertTrue(mysqlWithPort.startsWith("jdbc:mysql://127.0.0.1:3307/crm"), mysqlWithPort);

        assertEquals("jdbc:postgresql://h:5432/db",
                support.buildJdbcUrl("postgresql", "h", null, "db", null));
        assertEquals("jdbc:oracle:thin:@h:1521:orcl",
                support.buildJdbcUrl("oracle", "h", null, "orcl", null));
        assertEquals("jdbc:sqlserver://h:1433;databaseName=db",
                support.buildJdbcUrl("sqlserver", "h", null, "db", null));

        assertEquals("jdbc:mysql://custom:3306/x",
                support.buildJdbcUrl("mysql", "ignored", null, "ignored", "jdbc:mysql://custom:3306/x"));
    }

    @Test
    @DisplayName("JDBC URL 拼装：缺 host 或不支持的类型必须报错，不能静默生成错误连接串")
    void buildJdbcUrlInvalid() {
        CustomException noHost = assertThrows(CustomException.class,
                () -> support.buildJdbcUrl("mysql", null, null, "db", null));
        assertTrue(noHost.getMsg().contains("host"));

        CustomException unsupported = assertThrows(CustomException.class,
                () -> support.buildJdbcUrl("custom", "h", null, "db", null));
        assertTrue(unsupported.getMsg().contains("jdbcUrl"));
    }

    @Test
    @DisplayName("SQL 归一化：剥离最外层 script 标签，允许大小写与多余空格")
    void stripScriptTags() {
        assertEquals("SELECT 1 FROM t",
                McpToolSupport.stripScriptTags("<script>SELECT 1 FROM t</script>"));
        assertEquals("SELECT 1 FROM t",
                McpToolSupport.stripScriptTags("  <SCRIPT>  SELECT 1 FROM t  </SCRIPT>  "));
        assertEquals("SELECT 1 FROM t", McpToolSupport.stripScriptTags("SELECT 1 FROM t"));
        assertEquals("<if test=\"a != null\">x</if>",
                McpToolSupport.stripScriptTags("<script><if test=\"a != null\">x</if></script>"));
        assertNull(McpToolSupport.stripScriptTags(null));
    }

    @Test
    @DisplayName("结果裁剪：限制行数/列数/单值长度，并标记截断")
    void trimQueryResult() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i);
            row.put("name", "n" + i);
            rows.add(row);
        }
        Map<String, Object> output = McpToolSupport.trimQueryResult(rows, 20, 30, 200);

        assertEquals(25, output.get("rowCount"));
        assertEquals(List.of("id", "name"), output.get("columns"));
        assertEquals(20, ((List<?>) output.get("rows")).size());
        assertEquals(Boolean.TRUE, output.get("truncated"));
    }

    @Test
    @DisplayName("结果裁剪：非结果集返回受影响行数")
    void trimQueryResultAffectedRows() {
        Map<String, Object> output = McpToolSupport.trimQueryResult(3, 20, 30, 200);
        assertEquals(3, output.get("affectedRows"));
    }

    @Test
    @DisplayName("结果裁剪：单值超长截断、列数超限裁剪")
    void trimQueryResultColumnLimit() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("long", "y".repeat(300));
        for (int i = 0; i < 40; i++) {
            row.put("c" + i, i);
        }
        Map<String, Object> output = McpToolSupport.trimQueryResult(List.of(row), 20, 5, 10);
        List<?> trimmedRows = (List<?>) output.get("rows");
        Map<?, ?> trimmed = (Map<?, ?>) trimmedRows.get(0);
        assertEquals(5, trimmed.size());
        assertEquals("yyyyyyyyyy...(已截断)", trimmed.get("long"));
    }

    @Test
    @DisplayName("bindUser：业务体内可见归属用户，结束后清理线程上下文")
    void bindUser() {
        AtomicReference<String> userIdInAction = new AtomicReference<>();
        AtomicReference<String> userNameInAction = new AtomicReference<>();

        String result = support.bindUser("apigo_test", () -> {
            userIdInAction.set(UserThreadLocal.getUserId());
            userNameInAction.set(String.valueOf(UserThreadLocal.get().get("userName")));
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(McpTestFixture.USER_ID, userIdInAction.get(), "createBy/updateBy 依赖该上下文");
        assertTrue(userNameInAction.get().startsWith("mcp:"));
        assertNull(UserThreadLocal.get(), "必须清理，防止虚拟线程复用串号");
    }

    @Test
    @DisplayName("bindUser：业务体抛异常时同样清理上下文并向上抛出")
    void bindUserOnException() {
        CustomException ex = assertThrows(CustomException.class, () -> support.bindUser("apigo_test", () -> {
            throw new CustomException(44001, "无效的API");
        }));
        assertEquals(44001, ex.getCode());
        assertNull(UserThreadLocal.get());
    }

    @Test
    @DisplayName("bindWrite：读写令牌可执行变更类工具")
    void bindWriteAllowed() {
        McpTestFixture.bindReadWriteContext();

        String result = support.bindWrite("apigo_api_publish", "apiId=1", () -> {
            assertTrue(McpAuthContext.tokenName().contains("test"), "审计需要读到调用方标识");
            return "ok";
        });

        assertEquals("ok", result);
        assertNull(UserThreadLocal.get());
    }

    @Test
    @DisplayName("bindWrite：只读令牌被拒绝，业务体不执行")
    void bindWriteDeniedForReadOnly() {
        McpTestFixture.bindReadOnlyContext();
        AtomicReference<Boolean> executed = new AtomicReference<>(false);

        CustomException ex = assertThrows(CustomException.class,
                () -> support.bindWrite("apigo_api_publish", null, () -> {
                    executed.set(true);
                    return "ok";
                }));

        assertEquals(ErrorStatusEnum.FORBID_OPERATE.getCode(), ex.getCode());
        assertTrue(ex.getMsg().contains("只读"));
        assertEquals(false, executed.get(), "拒绝后不得触碰 service");
    }

    @Test
    @DisplayName("bindWrite：无鉴权上下文时 fail-closed（按只读处理）")
    void bindWriteDeniedWithoutContext() {
        AtomicReference<Boolean> executed = new AtomicReference<>(false);

        CustomException ex = assertThrows(CustomException.class,
                () -> support.bindWrite("apigo_api_publish", null, () -> {
                    executed.set(true);
                    return "ok";
                }));

        assertEquals(ErrorStatusEnum.FORBID_OPERATE.getCode(), ex.getCode());
        assertTrue(ex.getMsg().contains("未检测到"));
        assertEquals(false, executed.get());
    }

    @Test
    @DisplayName("高危操作确认闸门：未确认直接拒绝，confirm=true 放行")
    void requireConfirmed() {
        CustomException none = assertThrows(CustomException.class,
                () -> McpToolSupport.requireConfirmed(null, "发布接口"));
        assertEquals(ErrorStatusEnum.FORBID_OPERATE.getCode(), none.getCode());
        assertTrue(none.getMsg().contains("发布接口"));

        assertThrows(CustomException.class, () -> McpToolSupport.requireConfirmed(false, "发布接口"));
        assertDoesNotThrow(() -> McpToolSupport.requireConfirmed(true, "发布接口"));
    }

    @Test
    @DisplayName("paramsJson 解析：合法 JSON 返回 Map，非法 JSON 抛业务异常")
    void parseParamsJson() {
        Map<String, Object> parsed = McpToolSupport.parseParamsJson(McpTestFixture.jsonMapper(),
                "{\"id\": 1, \"name\": \"x\"}", "paramsJson");
        assertEquals(1, parsed.get("id"));
        assertEquals("x", parsed.get("name"));

        assertTrue(McpToolSupport.parseParamsJson(McpTestFixture.jsonMapper(), null, "paramsJson").isEmpty());

        CustomException ex = assertThrows(CustomException.class,
                () -> McpToolSupport.parseParamsJson(McpTestFixture.jsonMapper(), "not-a-json", "paramsJson"));
        assertEquals(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("结果裁剪：数字与布尔保留原始类型，避免模型误判为字符串")
    void trimQueryResultKeepsTypes() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("count", 128);
        row.put("flag", true);
        row.put("name", "x".repeat(40));

        Map<String, Object> output = McpToolSupport.trimQueryResult(List.of(row), 20, 30, 10);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) output.get("rows");
        Map<String, Object> trimmed = rows.get(0);
        assertEquals(128, trimmed.get("count"));
        assertEquals(true, trimmed.get("flag"));
        assertEquals("xxxxxxxxxx...(已截断)", trimmed.get("name"));
    }

    @Test
    @DisplayName("结果写入：null 值不写入，避免模型上下文出现无意义字段")
    void putIfNotNull() {
        Map<String, Object> map = McpToolSupport.newResult();
        McpToolSupport.putIfNotNull(map, "a", null);
        McpToolSupport.putIfNotNull(map, "b", 1);
        assertFalse(map.containsKey("a"));
        assertEquals(1, map.get("b"));
        assertTrue(McpToolSupport.result("k", null).isEmpty());
    }
}
