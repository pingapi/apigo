package cn.crabc.core.datasource.util;

import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;

/**
 * 带 SqlSource 缓存的 XML 语言驱动。
 * <p>
 * 用法：
 * <ul>
 *     <li>Mapper 方法上标注 {@code @Lang(CachedXmlLanguageDriver.class)}；</li>
 *     <li>或 {@code Configuration#setDefaultScriptingLanguage(CachedXmlLanguageDriver.class)}。</li>
 * </ul>
 * 仅在文本解析阶段复用 SqlSource，{@code ${}} 替换与参数绑定仍在每次
 * {@code getBoundSql} 时进行，语义与原生实现完全一致。
 *
 * @author yuqf
 */
public class CachedXmlLanguageDriver extends XMLLanguageDriver {

    @Override
    public SqlSource createSqlSource(Configuration configuration, String script, Class<?> parameterType) {
        if (script == null) {
            return super.createSqlSource(configuration, script, parameterType);
        }
        SqlSource cached = SqlSourceCache.get(configuration, script, parameterType);
        if (cached != null) {
            return cached;
        }
        SqlSource created = super.createSqlSource(configuration, script, parameterType);
        SqlSourceCache.put(configuration, script, parameterType, created);
        return created;
    }
}
