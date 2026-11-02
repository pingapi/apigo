package cn.crabc.core.app.config;

import cn.crabc.core.app.entity.dto.ApiInfoDTO;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    /**
     * 临时缓存对象(15分钟)
     * @return
     */
    @Bean("dataCache")
    public Cache<String, Object> dataCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(15, TimeUnit.MINUTES)
                .build();
    }

    /**
     * 缓存API对象 30分钟
     * @return
     */
    @Bean("apiCache")
    public Cache<String, ApiInfoDTO> apiCaffeine() {
        return Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .build();
    }


    /**
     * 元数据配置缓存管理器
     *
     * @return
     */
    /**
     * API 路径负缓存：不存在的路径短 TTL 记忆，
     * 防止未认证请求用随机路径持续打库（每请求 1 次 DB 查询）。
     * @return
     */
    @Bean("apiMissCache")
    public Cache<String, Boolean> apiMissCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .maximumSize(10_000)
                .build();
    }

    @Bean("metaDataManager")
    public CacheManager metaDataCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        Caffeine<Object, Object> caffeine = Caffeine.newBuilder()
                // 设置最后一次写入或访问后经过固定时间过期
                .expireAfterAccess(15, TimeUnit.MINUTES)
                // 元数据 key 为 datasourceId_schema[_table]，限制条目数防止大库把堆占满
                .maximumSize(5000);
        cacheManager.setCaffeine(caffeine);
        return cacheManager;
    }

}
