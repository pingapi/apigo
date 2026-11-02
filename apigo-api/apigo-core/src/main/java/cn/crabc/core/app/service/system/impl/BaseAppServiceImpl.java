package cn.crabc.core.app.service.system.impl;

import cn.crabc.core.app.entity.BaseApp;
import cn.crabc.core.app.entity.dto.ApiInfoDTO;
import cn.crabc.core.app.mapper.BaseAppMapper;
import cn.crabc.core.app.service.system.IBaseAppService;
import cn.crabc.core.app.util.ResourceOwnerGuard;
import cn.crabc.core.datasource.util.PageInfo;
import cn.crabc.core.app.util.UserThreadLocal;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 应用 服务实现
 *
 * @author yuqf
 */
@Service
public class BaseAppServiceImpl implements IBaseAppService {

    /**
     * 密钥掩码前缀，与 MCP 工具层保持一致。
     */
    private static final String MASK_PREFIX = "******";

    @Autowired
    private BaseAppMapper baseAppMapper;
    @Autowired
    @Qualifier("apiCache")
    Cache<String, ApiInfoDTO> apiInfoCache;
    @Autowired
    private ResourceOwnerGuard ownerGuard;

    @Override
    public PageInfo<BaseApp> appPage(String appName, String appCode, Integer pageNum, Integer pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<BaseApp> list = baseAppMapper.selectList(appName, ownerGuard.ownerScope());
        maskSecrets(list);
        return new PageInfo<>(list, pageNum, pageSize);
    }

    @Override
    public List<BaseApp> appList(String appName) {
        List<BaseApp> list = baseAppMapper.selectList(appName, ownerGuard.ownerScope());
        maskSecrets(list);
        return list;
    }

    @Override
    public Integer addApp(BaseApp app) {
        app.setStrategyType("white");
        app.setEnabled(1);
        app.setCreateBy(UserThreadLocal.getUserId());
        app.setCreateTime(new Date());
        app.setUpdateTime(new Date());
        return baseAppMapper.insert(app);
    }

    @Override
    public Integer updateApp(BaseApp app) {
        ownerGuard.assertApp(app.getAppId());
        // 列表接口只返回掩码，前端回显可能把掩码原样提交回来：此时保留原密钥，避免被掩码覆盖
        if (app.getAppSecret() != null
                && (app.getAppSecret().isBlank() || app.getAppSecret().startsWith(MASK_PREFIX))) {
            app.setAppSecret(null);
        }
        app.setUpdateTime(new Date());
        app.setUpdateBy(UserThreadLocal.getUserId());
        baseAppMapper.update(app);
        apiInfoCache.invalidateAll();
        return 1;
    }

    @Override
    public Integer deleteApp(Long appId) {
        ownerGuard.assertApp(appId);
        baseAppMapper.delete(appId);
        apiInfoCache.invalidateAll();
        return 1;
    }

    /**
     * app_secret 不允许通过列表接口明文返回：只保留末 4 位用于人工核对。
     */
    private void maskSecrets(List<BaseApp> apps) {
        if (apps == null) {
            return;
        }
        for (BaseApp app : apps) {
            app.setAppSecret(maskSecret(app.getAppSecret()));
        }
    }

    private String maskSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return secret;
        }
        if (secret.length() <= 8) {
            return MASK_PREFIX;
        }
        return MASK_PREFIX + secret.substring(secret.length() - 4);
    }

}
