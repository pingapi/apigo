package cn.crabc.core.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据插件链执行器
 * <p>
 * 解析 base_api_info.plugin_config 配置 JSON，按 {@link PluginKeys#ORDER} 顺序
 * 依次应用已启用的插件。失败策略为 fail-fast（配置不可用或插件执行出错均抛出
 * {@link DataPluginException}），避免返回未经脱敏/过滤的"降级数据"；
 * 对外只暴露泛化原因，详细堆栈写入服务端日志。
 *
 * @author yuqf
 */
@Component
public class DataPluginExecutor {

    private static final Logger log = LoggerFactory.getLogger(DataPluginExecutor.class);

    private static final TypeReference<Map<String, Object>> CONFIG_TYPE = new TypeReference<>() {
    };

    /**
     * 配置解析缓存：key 为完整 JSON 文本（无哈希碰撞风险），LRU 有界。
     * 插件配置随 API 元数据缓存在每次请求重复反序列化，这里消除该开销；
     * 解析结果只读使用（插件不修改配置 Map），可安全共享。
     */
    private static final int MAX_CONFIG_CACHE = 512;

    private final Map<String, Map<String, Object>> configCache = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                    return size() > MAX_CONFIG_CACHE;
                }
            });

    private final JsonMapper jsonMapper;

    /** 插件注册表：key -> 插件实例 */
    private final Map<String, DataPlugin> pluginRegistry = new HashMap<>();

    public DataPluginExecutor(JsonMapper jsonMapper, List<DataPlugin> plugins) {
        this.jsonMapper = jsonMapper;
        for (DataPlugin plugin : plugins) {
            pluginRegistry.put(plugin.getName(), plugin);
        }
        log.info("数据插件注册完成：{}", pluginRegistry.keySet());
    }

    /**
     * 执行插件链
     *
     * @param pluginConfigJson 插件配置 JSON 字符串（可为空）
     * @param data             SQL 执行结果
     * @param requestParams    请求参数
     * @return 处理后的数据；无需处理时返回原数据
     */
    public Object execute(String pluginConfigJson, Object data, Map<String, Object> requestParams) {
        if (data == null || pluginConfigJson == null || pluginConfigJson.isBlank()) {
            return data;
        }
        Map<String, Object> config = configCache.get(pluginConfigJson);
        if (config == null) {
            try {
                config = jsonMapper.readValue(pluginConfigJson, CONFIG_TYPE);
            } catch (Exception e) {
                // fail-fast：配置不可用时不得静默返回未处理数据
                log.error("数据插件配置解析失败：{}", e.getMessage());
                throw new DataPluginException(null, "数据插件配置不可用，请联系管理员", e);
            }
            configCache.put(pluginConfigJson, config);
        }
        if (config.isEmpty()) {
            return data;
        }
        warnMutexConflict(config);
        for (String key : PluginKeys.ORDER) {
            Object node = config.get(key);
            if (!(node instanceof Map<?, ?> rawNode)) {
                continue;
            }
            if (!isEnabled(rawNode)) {
                continue;
            }
            DataPlugin plugin = pluginRegistry.get(key);
            if (plugin == null) {
                // 配置中存在但当前版本未实现的插件：显式告警，避免用户误以为已生效
                log.warn("数据插件[{}]已启用但当前版本未实现，已跳过", key);
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> pluginConfig = (Map<String, Object>) rawNode;
            try {
                data = plugin.apply(data, pluginConfig, requestParams);
            } catch (DataPluginException e) {
                throw e;
            } catch (Exception e) {
                // 详细原因只进日志，对外泛化（异常信息可能包含行数据值）
                log.error("数据插件[{}]执行失败：{}", key, e.getMessage(), e);
                throw new DataPluginException(key, "数据插件[" + key + "]执行失败，请检查插件配置", e);
            }
        }
        return data;
    }

    /**
     * 结果转换类插件互斥：前端已约束，此处对历史/旁路数据兜底告警
     */
    private void warnMutexConflict(Map<String, Object> config) {
        if (isEnabledNode(config.get(PluginKeys.TREE_TRANSFORM)) && isEnabledNode(config.get(PluginKeys.SINGLE_OBJECT))) {
            log.warn("数据插件配置冲突：{} 与 {} 同时启用，将按插件链顺序依次执行",
                    PluginKeys.TREE_TRANSFORM, PluginKeys.SINGLE_OBJECT);
        }
    }

    private boolean isEnabledNode(Object node) {
        return node instanceof Map<?, ?> map && isEnabled(map);
    }

    /**
     * 判断插件节点是否启用
     */
    private boolean isEnabled(Map<?, ?> node) {
        Object enabled = node.get("enabled");
        if (enabled instanceof Boolean b) {
            return b;
        }
        return enabled != null && Boolean.parseBoolean(enabled.toString());
    }
}
