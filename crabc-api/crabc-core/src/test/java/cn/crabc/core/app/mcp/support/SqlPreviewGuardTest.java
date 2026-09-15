package cn.crabc.core.app.mcp.support;

import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL 试跑安全闸门测试。
 * <p>
 * 「AI 只能跑 SELECT」是红线：{@code JdbcStatement.validateSqlSafety} 对多语句只 log.warn，
 * 因此这些用例是保障该红线不被绕过的主要手段。
 *
 * @author yuqf
 */
class SqlPreviewGuardTest {

    private static final int REJECT_CODE = ErrorStatusEnum.API_SQL_ERROR.getCode();

    @Test
    @DisplayName("查询语句放行：普通 SELECT、带表引用的 SELECT、无表引用的 SELECT")
    void allowSelect() {
        assertDoesNotThrow(() -> SqlPreviewGuard.validateSelectOnly("SELECT id FROM t_user", "mysql"));
        assertDoesNotThrow(() -> SqlPreviewGuard.validateSelectOnly("select count(1) from t_order where id = 1", "mysql"));
        assertDoesNotThrow(() -> SqlPreviewGuard.validateSelectOnly("SELECT 1", "mysql"));
        assertDoesNotThrow(() -> SqlPreviewGuard.validateSelectOnly("SELECT NOW()", "postgresql"));
    }

    @Test
    @DisplayName("动态 SQL 与占位符放行：走关键字兜底，不触发 Druid 解析报错")
    void allowDynamicSql() {
        assertDoesNotThrow(() -> SqlPreviewGuard.validateSelectOnly(
                "SELECT * FROM t_user <if test=\"name != null\">AND name = #{name}</if>", "mysql"));
        assertDoesNotThrow(() -> SqlPreviewGuard.validateSelectOnly(
                "SELECT * FROM t_user WHERE id IN <foreach collection=\"ids\" item=\"id\" open=\"(\" close=\")\">#{id}</foreach>",
                "mysql"));
        assertDoesNotThrow(() -> SqlPreviewGuard.validateSelectOnly(
                "SELECT * FROM t_user WHERE id = #{id}", "mysql"));
        assertDoesNotThrow(() -> SqlPreviewGuard.validateSelectOnly(
                "<script>SELECT * FROM t_user WHERE id = #{id}</script>", "mysql"));
    }

    @Test
    @DisplayName("DML 与 DDL 全部拒绝（含带占位符的写法）")
    void rejectMutation() {
        for (String sql : new String[]{
                "UPDATE t_user SET name = 'x'",
                "DELETE FROM t_user",
                "INSERT INTO t_user(id) VALUES (1)",
                "DROP TABLE t_user",
                "TRUNCATE TABLE t_user",
                "ALTER TABLE t_user ADD COLUMN c INT",
                "UPDATE t_user SET name = #{name} WHERE id = #{id}",
                "INSERT INTO t_user(id) VALUES (#{id})"}) {
            CustomException ex = assertThrows(CustomException.class,
                    () -> SqlPreviewGuard.validateSelectOnly(sql, "mysql"), "应拒绝：" + sql);
            assertEquals(REJECT_CODE, ex.getCode());
        }
    }

    @Test
    @DisplayName("多语句拒绝：防止用 SELECT 打头夹带 DML")
    void rejectMultipleStatements() {
        CustomException ex = assertThrows(CustomException.class,
                () -> SqlPreviewGuard.validateSelectOnly("SELECT id FROM t_user; DELETE FROM t_user", "mysql"));
        assertEquals(REJECT_CODE, ex.getCode());
        assertTrue(ex.getMsg().contains("单条"));

        assertThrows(CustomException.class,
                () -> SqlPreviewGuard.validateSelectOnly("SELECT id FROM t_user;\nUPDATE t_user SET name='x'", "mysql"));
    }

    @Test
    @DisplayName("CTE 夹带 DML 拒绝：WITH ... DELETE 不能被当成查询")
    void rejectCteMutation() {
        CustomException ex = assertThrows(CustomException.class,
                () -> SqlPreviewGuard.validateSelectOnly("WITH recent AS (SELECT 1 AS id) DELETE FROM t_user", "mysql"));
        assertEquals(REJECT_CODE, ex.getCode());
    }

    @Test
    @DisplayName("空 SQL 拒绝")
    void rejectBlank() {
        assertEquals(REJECT_CODE, assertThrows(CustomException.class,
                () -> SqlPreviewGuard.validateSelectOnly("   ", "mysql")).getCode());
        assertEquals(REJECT_CODE, assertThrows(CustomException.class,
                () -> SqlPreviewGuard.validateSelectOnly(null, "mysql")).getCode());
    }

    @Test
    @DisplayName("追加 LIMIT：无 LIMIT 时追加，有 LIMIT 时保持原样")
    void appendLimit() {
        assertEquals("SELECT id FROM t_user LIMIT 100",
                SqlPreviewGuard.appendLimitIfAbsent("SELECT id FROM t_user", 100));
        assertEquals("SELECT id FROM t_user LIMIT 10",
                SqlPreviewGuard.appendLimitIfAbsent("SELECT id FROM t_user LIMIT 10", 100));
        assertEquals("SELECT id FROM t_user LIMIT #{size}",
                SqlPreviewGuard.appendLimitIfAbsent("SELECT id FROM t_user LIMIT #{size}", 100));
    }

    @Test
    @DisplayName("追加 LIMIT：去掉末尾分号")
    void appendLimitTrimSemicolon() {
        assertEquals("SELECT id FROM t_user LIMIT 100",
                SqlPreviewGuard.appendLimitIfAbsent("SELECT id FROM t_user;", 100));
    }

    @Test
    @DisplayName("追加 LIMIT：script 包裹时插到闭合标签之前")
    void appendLimitInsideScript() {
        String result = SqlPreviewGuard.appendLimitIfAbsent("<script>SELECT id FROM t_user</script>", 100);
        assertTrue(result.contains("LIMIT 100"), result);
        assertTrue(result.trim().endsWith("</script>"), result);
    }

    @Test
    @DisplayName("追加 LIMIT：空值透传")
    void appendLimitBlank() {
        assertNull(SqlPreviewGuard.appendLimitIfAbsent(null, 100));
    }

    @Test
    @DisplayName("追加 LIMIT：末尾注释不能吞掉 LIMIT（否则行数限制会被绕过）")
    void appendLimitBeforeTrailingComment() {
        assertEquals("SELECT id FROM t_user LIMIT 100",
                SqlPreviewGuard.appendLimitIfAbsent("SELECT id FROM t_user -- 只查一部分", 100));
        assertEquals("SELECT id FROM t_user LIMIT 100",
                SqlPreviewGuard.appendLimitIfAbsent("SELECT id FROM t_user /* trailing */", 100));
        assertEquals("SELECT id FROM t_user LIMIT 100",
                SqlPreviewGuard.appendLimitIfAbsent("SELECT id FROM t_user # mysql 注释", 100));
        // 注释里的分号不应影响去分号逻辑
        assertEquals("SELECT id FROM t_user LIMIT 100",
                SqlPreviewGuard.appendLimitIfAbsent("SELECT id FROM t_user -- a;b;", 100));
        // MyBatis 的 #{param} 不能被当成 MySQL 的 # 注释而误删
        assertEquals("SELECT id FROM t_user WHERE id = #{id} LIMIT 100",
                SqlPreviewGuard.appendLimitIfAbsent("SELECT id FROM t_user WHERE id = #{id} -- 注释", 100));
    }

    @Test
    @DisplayName("试跑闸门：拒绝读取服务端文件/执行命令的函数")
    void rejectFileAccessFunctions() {
        for (String sql : new String[]{
                "SELECT read_text('/etc/passwd') AS c",
                "SELECT * FROM read_csv_auto('/etc/passwd')",
                "SELECT CSVREAD('/etc/passwd')",
                "SELECT LOAD_FILE('/etc/passwd')",
                "SELECT pg_read_file('/etc/passwd')"}) {
            CustomException ex = assertThrows(CustomException.class,
                    () -> SqlPreviewGuard.validatePreviewSql(sql, "mysql"), "应拒绝：" + sql);
            assertEquals(REJECT_CODE, ex.getCode());
        }
    }

    @Test
    @DisplayName("试跑闸门：正常查询放行，非查询仍被拒绝")
    void validatePreviewSql() {
        assertDoesNotThrow(() -> SqlPreviewGuard.validatePreviewSql("SELECT id, name FROM t_user", "mysql"));
        assertDoesNotThrow(() -> SqlPreviewGuard.validatePreviewSql(
                "SELECT * FROM t_user WHERE id = #{id}", "mysql"));
        assertThrows(CustomException.class,
                () -> SqlPreviewGuard.validatePreviewSql("DELETE FROM t_user", "mysql"));
    }

    @Test
    @DisplayName("非抛异常的查询判定：供创建接口时决定是否需要 DML 确认")
    void isSelectOnly() {
        assertTrue(SqlPreviewGuard.isSelectOnly("SELECT id FROM t_user", "mysql"));
        assertTrue(SqlPreviewGuard.isSelectOnly("WITH x AS (SELECT 1) SELECT * FROM x", "mysql"));
        assertFalse(SqlPreviewGuard.isSelectOnly("UPDATE t_user SET name = 'x'", "mysql"));
        assertFalse(SqlPreviewGuard.isSelectOnly("DELETE FROM t_user", "mysql"));
        assertFalse(SqlPreviewGuard.isSelectOnly("CREATE TABLE t (id INT)", "mysql"));
    }
}
