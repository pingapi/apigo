package cn.crabc.core.datasource.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;

/**
 * SqlSource 缓存。
 * <p>
 * 背景：MyBatis 的 {@code ProviderSqlSource#getBoundSql} 每次调用都会重新
 * {@code languageDriver.createSqlSource(...)}：含 {@code <if>/<foreach>} 的 SQL
 * 要重新走 XMLScriptBuilder 解析并构建 OGNL 表达式树，是动态 SQL 场景下每请求
 * 最大的 CPU 开销。MyBatis 自身不做这层缓存，这里按 SQL 文本补上。
 * <p>
 * 缓存键包含 Configuration 身份 + 参数类型 + <b>完整 SQL 文本</b>：
 * <ul>
 *     <li>用完整文本而非 hash，杜绝哈希碰撞导致执行到另一条 SQL；</li>
 *     <li>{@code SqlSource} 本身不可变且线程安全，{@code BoundSql} 仍按请求新建，参数互不干扰。</li>
 * </ul>
 * 底层为 Caffeine（W-TinyLFU 有界淘汰）：读路径无全局锁，替代旧的
 * synchronizedMap+LinkedHashMap，虚拟线程高并发下不再串行化缓存读取。
 *
 * @author yuqf
 */
public final class SqlSourceCache {

    /**
     * 缓存条目上限：按单个 SqlSource 数 KB 估算，总占用可控。
     */
    private static final int MAX_ENTRIES = 512;

    private static final Cache<String, SqlSource> CACHE = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .build();

    private SqlSourceCache() {
    }

    /**
     * 取缓存的 SqlSource。
     *
     * @param configuration MyBatis 配置（不同 SqlSessionFactory 不能混用）
     * @param script        交给 LanguageDriver 解析的脚本文本
     * @param parameterType 参数类型
     * @return 命中的 SqlSource；未命中返回 null
     */
    public static SqlSource get(Configuration configuration, String script, Class<?> parameterType) {
        return CACHE.getIfPresent(key(configuration, script, parameterType));
    }

    /**
     * 放入缓存（SqlSource 为 null 时忽略）。
     */
    public static void put(Configuration configuration, String script, Class<?> parameterType, SqlSource sqlSource) {
        if (sqlSource == null || script == null) {
            return;
        }
        CACHE.put(key(configuration, script, parameterType), sqlSource);
    }

    /**
     * 当前缓存条目数（用于监控与单测断言）。
     */
    public static int size() {
        return CACHE.asMap().size();
    }

    /**
     * 清空缓存（测试用）。
     */
    public static void clear() {
        CACHE.invalidateAll();
    }

    private static String key(Configuration configuration, String script, Class<?> parameterType) {
        return System.identityHashCode(configuration)
                + "|" + (parameterType == null ? "" : parameterType.getName())
                + "|" + script;
    }
}
