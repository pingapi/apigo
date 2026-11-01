package cn.crabc.core.app.service.system.impl;

import cn.crabc.core.app.entity.BaseDatasource;
import cn.crabc.core.app.mapper.BaseDataSourceMapper;
import cn.crabc.core.app.service.core.IBaseDataService;
import cn.crabc.core.app.service.system.IBaseDataSourceService;
import cn.crabc.core.app.util.SQLUtil;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Objects;

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
        for (BaseDataSource dataSource : baseDataSources) {
            DataSource ds = DataSourceManager.DATA_SOURCE_POOL_JDBC.get(dataSource.getDatasourceId().toString());
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
    }

    @Override
    public List<BaseDataSource> getList() {
        return dataSourceMapper.list();
    }

    @Override
    public PageInfo<BaseDatasource> getDataSourcePage(String dataSourceName, int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<BaseDatasource> list = dataSourceMapper.selectList(dataSourceName);
        return new PageInfo<>(list, pageNum, pageSize);
    }

    @Override
    public List<BaseDatasource> getDataSourceList(String dataSourceName) {
        return dataSourceMapper.selectList(dataSourceName);
    }

    @Override
    public BaseDatasource getDataSource(Integer dataSourceId) {
        return dataSourceMapper.selectOne(dataSourceId);
    }

    @Override
    public Integer addDataSource(BaseDatasource dataSource) {
        dataSource.setClassify("jdbc");
        dataSource.setCreateBy(UserThreadLocal.getUserId());
        dataSource.setCreateTime(new Date());
        dataSource.setUpdateTime(new Date());
        // 判断是否是自定义
        if ("custom".equals(dataSource.getDatasourceType())){
            String dataSourceType = SQLUtil.getDataSourceType(dataSource.getJdbcUrl());
            dataSource.setDatasourceType(dataSourceType);
        }
        Integer result = dataSourceMapper.insertDataSource(dataSource);
        this.addCache(dataSource);
        return result;
    }

    @Override
    public Integer updateDataSource(BaseDatasource dataSource) {
        this.parsePassword(dataSource);
        dataSource.setUpdateBy(UserThreadLocal.getUserId());
        dataSource.setUpdateTime(new Date());
        dataSource.setPassword(Base64.getEncoder().encodeToString(dataSource.getPassword().getBytes()));
        this.addCache(dataSource);
        return dataSourceMapper.updateDataSource(dataSource);
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
        // 删除缓存中的数据源
        dataSourceManager.remove(dataSourceId.toString());
        return dataSourceMapper.deleteDataSource(dataSourceId);
    }

    @Override
    public String test(BaseDatasource dataSource) {
        if ("custom".equals(dataSource.getDatasourceType())){
            String dataSourceType = SQLUtil.getDataSourceType(dataSource.getJdbcUrl());
            dataSource.setDatasourceType(dataSourceType);
        }
        // 密码只解码到副本上：写回入参会让"先测连、再落库"的调用方把明文存进库，
        // 随后 addCache 解码明文抛 Illegal base64 character 40，数据源进不了连接池缓存。
        BaseDatasource probe = new BaseDatasource();
        BeanUtils.copyProperties(dataSource, probe);
        this.parsePassword(probe);
        return iBaseDataService.testConnection(probe);
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
