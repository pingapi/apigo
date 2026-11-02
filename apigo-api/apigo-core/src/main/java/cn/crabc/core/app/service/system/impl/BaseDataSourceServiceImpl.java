package cn.crabc.core.app.service.system.impl;

import cn.crabc.core.app.entity.BaseDatasource;
import cn.crabc.core.app.mapper.BaseDataSourceMapper;
import cn.crabc.core.app.service.core.IBaseDataService;
import cn.crabc.core.app.service.system.IBaseDataSourceService;
import cn.crabc.core.app.support.DatasourceGuard;
import cn.crabc.core.app.util.ResourceOwnerGuard;
import cn.crabc.core.app.util.SQLUtil;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import cn.crabc.core.datasource.util.PageInfo;
import cn.crabc.core.app.util.UserThreadLocal;
import cn.crabc.core.datasource.driver.DataSourceManager;
import cn.crabc.core.datasource.driver.jdbc.DuckDbDataSource;
import cn.crabc.core.spi.bean.BaseDataSource;
import com.github.pagehelper.PageHelper;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 数据源 服务实现
 *
 * @author yuqf
 */
@Service
public class BaseDataSourceServiceImpl implements IBaseDataSourceService {

    private static Logger log = LoggerFactory.getLogger(BaseDataSourceServiceImpl.class);

    @Autowired
    private BaseDataSourceMapper dataSourceMapper;
    @Autowired
    private DataSourceManager dataSourceManager;
    @Autowired
    private IBaseDataService iBaseDataService;
    @Autowired
    private ResourceOwnerGuard ownerGuard;
    @Autowired
    private DatasourceGuard datasourceGuard;
    @Autowired
    @Qualifier("metaDataManager")
    private CacheManager metaDataCacheManager;

    /**
     * 启动加载
     */
    @PostConstruct
    public void load() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                init();
            }
        });
        t.start();
    }

    @Override
    @Scheduled(cron = "${crabc.corn.api:0 0/5 * * * ?}")
    public void init() {
        List<BaseDataSource> baseDataSources = this.getList();
        Set<String> aliveIds = new HashSet<>(baseDataSources.size());
        for (BaseDataSource dataSource : baseDataSources) {
            String datasourceId = dataSource.getDatasourceId().toString();
            aliveIds.add(datasourceId);
            DataSource ds = DataSourceManager.DATA_SOURCE_POOL_JDBC.get(datasourceId);
            try {
                DataSourceIdentity oldIdentity = buildIdentity(ds);
                byte[] decode = Base64.getDecoder().decode(dataSource.getPassword());
                dataSource.setPassword(new String(decode));
                // 判断数据库连接关键属性是否有修改，有修改则刷新缓存；空快照表示缓存不存在或类型未知，需要重建。
                if (oldIdentity.exists() && oldIdentity.sameAs(dataSource)) {
                    continue;
                }
                dataSourceManager.createDataSource(dataSource);
            }catch (Exception e) {
                log.error("-数据源加载异常，dataSourceId:{}",dataSource.getDatasourceId(),e);
            }
        }
        this.reconcilePools(aliveIds);
    }

    /**
     * 清理"库中已删除、内存池仍存在"的僵尸连接池。
     * <p>
     * 删除数据源与定时刷新存在竞态：刷新线程拿到旧快照后仍可能为已删行建池，
     * 之后该行不再出现在 list() 中，池便永久驻留（并保持 keepalive 连接）。
     *
     * @param aliveIds 本轮库中仍存在的数据源ID集合
     */
    private void reconcilePools(Set<String> aliveIds) {
        for (String pooledId : new ArrayList<>(DataSourceManager.DATA_SOURCE_POOL_JDBC.keySet())) {
            if (aliveIds.contains(pooledId)) {
                continue;
            }
            Integer datasourceId;
            try {
                datasourceId = Integer.valueOf(pooledId);
            } catch (NumberFormatException e) {
                // 非平台数据源ID（理论上不存在），跳过避免误删
                continue;
            }
            try {
                // 再查一次库：本轮快照可能未覆盖到刚新增的数据源
                if (dataSourceMapper.selectOne(datasourceId) != null) {
                    continue;
                }
                log.warn("检测到库中已不存在的连接池，执行清理，datasourceId:{}", pooledId);
                dataSourceManager.remove(pooledId);
            } catch (Exception e) {
                log.warn("清理僵尸连接池失败，datasourceId:{}", pooledId, e);
            }
        }
    }

    @Override
    public List<BaseDataSource> getList() {
        return dataSourceMapper.list();
    }

    @Override
    public PageInfo<BaseDatasource> getDataSourcePage(String dataSourceName, int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<BaseDatasource> list = dataSourceMapper.selectList(dataSourceName, ownerGuard.ownerScope());
        return new PageInfo<>(list, pageNum, pageSize);
    }

    @Override
    public List<BaseDatasource> getDataSourceList(String dataSourceName) {
        return dataSourceMapper.selectList(dataSourceName, ownerGuard.ownerScope());
    }

    @Override
    public BaseDatasource getDataSource(Integer dataSourceId) {
        // 详情包含连接串与密码，跨租户读取属于越权（MCP 工具与元数据浏览都走这里）
        return ownerGuard.assertDataSource(dataSourceId);
    }

    @Override
    public Integer addDataSource(BaseDatasource dataSource) {
        dataSource.setClassify("jdbc");
        dataSource.setCreateBy(UserThreadLocal.getUserId());
        dataSource.setCreateTime(new Date());
        dataSource.setUpdateTime(new Date());
        // 判断是否是自定义
        this.resolveDatasourceType(dataSource);
        this.validateJdbcUrl(dataSource.getDatasourceType(), dataSource.getJdbcUrl());
        this.validatePasswordEncoding(dataSource.getPassword());
        Integer result = dataSourceMapper.insertDataSource(dataSource);
        try {
            this.addCache(dataSource);
        } catch (RuntimeException e) {
            // 建池失败回滚脏行：否则每 5 分钟 init 对该行 decode 失败一次，数据源永久不可用
            dataSourceMapper.deleteDataSource(dataSource.getDatasourceId());
            throw e;
        }
        return result;
    }

    @Override
    public Integer updateDataSource(BaseDatasource dataSource) {
        BaseDatasource old = ownerGuard.assertDataSource(dataSource.getDatasourceId());
        this.resolveDatasourceType(dataSource);
        // 局部更新时沿用库中连接串做校验，避免"只改备注"绕过守卫
        String jdbcUrl = dataSource.getJdbcUrl() == null || dataSource.getJdbcUrl().isBlank()
                ? old.getJdbcUrl() : dataSource.getJdbcUrl();
        String datasourceType = dataSource.getDatasourceType() == null || dataSource.getDatasourceType().isBlank()
                ? old.getDatasourceType() : dataSource.getDatasourceType();
        this.validateJdbcUrl(datasourceType, jdbcUrl);
        try {
            this.parsePassword(dataSource);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), "数据源密码必须为 Base64 编码");
        }
        dataSource.setUpdateBy(UserThreadLocal.getUserId());
        dataSource.setUpdateTime(new Date());
        dataSource.setPassword(Base64.getEncoder().encodeToString(dataSource.getPassword().getBytes()));
        this.addCache(dataSource);
        Integer result = dataSourceMapper.updateDataSource(dataSource);
        // 连接信息变更后元数据（schema/表/字段）可能已失效，清理缓存避免编辑器读到旧结构
        this.evictMetaDataCache();
        return result;
    }

    /**
     * 更新到缓存
     *
     * @param dataSource
     */
    private void addCache(BaseDatasource dataSource) {
        // 新增或更新数据源
        BaseDataSource ds = new BaseDataSource();
        BeanUtils.copyProperties(dataSource, ds);
        byte[] decode = Base64.getDecoder().decode(dataSource.getPassword());
        ds.setPassword(new String(decode));
        dataSourceManager.createDataSource(ds);
    }

    @Override
    public Integer deleteDataSource(Integer dataSourceId) {
        ownerGuard.assertDataSource(dataSourceId);
        // 先删库、后删池：删库失败时保留内存池，避免出现"库里有行、池已关闭"的不可用窗口。
        // 并发窗口由 init() 的僵尸池对账兜底。
        Integer deleted = dataSourceMapper.deleteDataSource(dataSourceId);
        if (deleted != null && deleted > 0) {
            dataSourceManager.remove(dataSourceId.toString());
            this.evictMetaDataCache();
        }
        return deleted;
    }

    /**
     * 清理元数据缓存（schema / 表 / 字段）。
     * <p>
     * 缓存 key 与具体数据源相关，但数据源变更本身是低频操作，
     * 直接整体清理比按前缀遍历更简单可靠。
     */
    private void evictMetaDataCache() {
        if (metaDataCacheManager == null) {
            return;
        }
        for (String cacheName : new String[]{"schemaCache", "tableCache", "columnsCache"}) {
            try {
                org.springframework.cache.Cache cache = metaDataCacheManager.getCache(cacheName);
                if (cache != null) {
                    cache.clear();
                }
            } catch (Exception e) {
                log.warn("清理元数据缓存失败，cache:{}, error:{}", cacheName, e.getMessage());
            }
        }
    }

    @Override
    public String test(BaseDatasource dataSource) {
        // 带上主键时只允许测试自己的数据源（否则等于用别人的凭据探测内网/库结构）
        if (dataSource.getDatasourceId() != null) {
            BaseDatasource stored = ownerGuard.assertDataSource(dataSource.getDatasourceId());
            // 前端/MCP 只传主键时，用库中已保存的连接串补齐后再校验
            if (dataSource.getJdbcUrl() == null || dataSource.getJdbcUrl().isBlank()) {
                dataSource.setJdbcUrl(stored.getJdbcUrl());
            }
            if (dataSource.getDatasourceType() == null || dataSource.getDatasourceType().isBlank()) {
                dataSource.setDatasourceType(stored.getDatasourceType());
            }
        }
        this.resolveDatasourceType(dataSource);
        this.validateJdbcUrl(dataSource.getDatasourceType(), dataSource.getJdbcUrl());
        // 密码只解码到副本上：写回入参会让"先测连、再落库"的调用方把明文存进库，
        // 随后 addCache 解码明文抛 Illegal base64 character 40，数据源进不了连接池缓存。
        BaseDatasource probe = new BaseDatasource();
        BeanUtils.copyProperties(dataSource, probe);
        this.parsePassword(probe);
        return iBaseDataService.testConnection(probe);
    }

    /**
     * custom 类型按 JDBC URL 反推具体类型；其它类型保持原值。
     */
    private void resolveDatasourceType(BaseDatasource dataSource) {
        if (!"custom".equalsIgnoreCase(dataSource.getDatasourceType())) {
            return;
        }
        try {
            dataSource.setDatasourceType(SQLUtil.getDataSourceType(dataSource.getJdbcUrl()));
        } catch (Exception e) {
            throw new CustomException(ErrorStatusEnum.FORBID_OPERATE.getCode(),
                    "无法根据 JDBC URL 识别数据源类型：" + e.getMessage());
        }
    }

    /**
     * 数据源连接参数统一校验：协议白名单 + 危险参数 + 禁止指向平台主库。
     *
     * @param datasourceType 数据源类型
     * @param jdbcUrl        JDBC 连接串
     */
    private void validateJdbcUrl(String datasourceType, String jdbcUrl) {        datasourceGuard.validateForManagement(datasourceType, jdbcUrl);
        // duckdb 是本地文件型引擎，可通过 SQL 读写服务器文件，仅允许管理员创建
        if ("duckdb".equalsIgnoreCase(datasourceType) && !UserThreadLocal.isAdmin()) {
            throw new CustomException(ErrorStatusEnum.FORBID_OPERATE.getCode(),
                    "duckdb 为本地文件型引擎，仅管理员可创建该类型数据源");
        }
    }

    /**
     * 入库前校验密码编码：平台约定 password 为 Base64，若把非法编码写进库，
     * 之后每轮定时刷新都会 decode 失败，该数据源永久无法加载。
     *
     * @param password Base64 编码后的密码（允许为空，交由驱动处理）
     */
    private void validatePasswordEncoding(String password) {
        if (password == null || password.isBlank()) {
            return;
        }
        try {
            Base64.getDecoder().decode(password);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), "数据源密码必须为 Base64 编码");
        }
    }

    /**
     * 更新/测试时解析数据库密码
     *
     * @param dataSource
     */
    private void parsePassword(BaseDatasource dataSource) {
        String password = dataSource.getPassword();
        String pwd = null;
        if (password != null && !password.trim().isEmpty()) {
            byte[] decode = Base64.getDecoder().decode(password);
            pwd = new String(decode);
        }else if (dataSource.getDatasourceId() != null){
            BaseDatasource baseDatasource = dataSourceMapper.selectOne(dataSource.getDatasourceId());
            byte[] decode = Base64.getDecoder().decode(baseDatasource.getPassword());
            pwd = new String(decode);
        }
        dataSource.setPassword(pwd);
    }

    /**
     * 判断数据源类型
     */
    private DataSourceIdentity buildIdentity(DataSource dataSource) {
        if (dataSource == null) {
            return DataSourceIdentity.empty();
        }
        if (dataSource instanceof HikariDataSource hikari) {
            return new DataSourceIdentity(hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword());
        }
        if (dataSource instanceof DuckDbDataSource duckDb) {
            return new DataSourceIdentity(duckDb.getJdbcUrl(), duckDb.getUsername(), duckDb.getPassword());
        }
        return DataSourceIdentity.empty();
    }

    /**
     * 数据源连接关键属性快照，仅用于判断缓存是否需要重建。
     */
    private static class DataSourceIdentity {

        private final String jdbcUrl;
        private final String username;
        private final String password;

        private DataSourceIdentity(String jdbcUrl, String username, String password) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
        }

        private static DataSourceIdentity empty() {
            return new DataSourceIdentity(null, null, null);
        }

        /**
         * 只有识别到缓存中的数据源类型时才允许复用，避免未知DataSource误判为未修改。
         */
        private boolean exists() {
            return jdbcUrl != null;
        }

        /**
         * 比较数据库连接关键属性，密码已在调用前解码成明文。
         */
        private boolean sameAs(BaseDataSource dataSource) {
            return dataSource != null
                    && equals(dataSource.getJdbcUrl(), jdbcUrl)
                    && equals(dataSource.getUsername(), username)
                    && equals(dataSource.getPassword(), password);
        }

        private boolean equals(String left, String right) {
            return Objects.equals(left, right);
        }
    }
}
