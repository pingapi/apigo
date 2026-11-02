package cn.crabc.core.app.service.system.impl;

import cn.crabc.core.app.entity.BaseDatasource;
import cn.crabc.core.app.mapper.BaseDataSourceMapper;
import cn.crabc.core.app.service.core.IBaseDataService;
import cn.crabc.core.app.support.DatasourceGuard;
import cn.crabc.core.datasource.driver.DataSourceManager;
import cn.crabc.core.spi.bean.BaseDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据源服务密码约定测试。
 * <p>
 * 平台约定：{@code base_datasource.password} 存 Base64，建连接池/测连时才解码成明文。
 * 因此服务方法不得把解码后的明文写回调用方传入的对象——否则按"先测连、再落库"的顺序
 * 调用时，库里会写入明文，随后 {@code addCache} 解码明文直接抛
 * {@code Illegal base64 character 40}，数据源永远进不了连接池缓存。
 *
 * @author yuqf
 */
class BaseDataSourceServiceImplTest {

    /** 明文密码，含 '@'：正是 Base64 解码器会拒绝的字符 */
    private static final String PLAIN = "P@ssw0rd";
    /** PLAIN 的 Base64（printf 'P@ssw0rd' | base64） */
    private static final String ENCODED = "UEBzc3cwcmQ=";

    private BaseDataSourceMapper dataSourceMapper;
    private DataSourceManager dataSourceManager;
    private IBaseDataService iBaseDataService;
    private BaseDataSourceServiceImpl service;

    @BeforeEach
    void setUp() {
        dataSourceMapper = mock(BaseDataSourceMapper.class);
        dataSourceManager = mock(DataSourceManager.class);
        iBaseDataService = mock(IBaseDataService.class);
        service = new BaseDataSourceServiceImpl();
        ReflectionTestUtils.setField(service, "dataSourceMapper", dataSourceMapper);
        ReflectionTestUtils.setField(service, "dataSourceManager", dataSourceManager);
        ReflectionTestUtils.setField(service, "iBaseDataService", iBaseDataService);
        // 连接串守卫：协议白名单 + 危险参数 + 禁止指向平台主库（未配置主库时只做前者）
        ReflectionTestUtils.setField(service, "datasourceGuard", new DatasourceGuard());
    }

    @AfterEach
    void tearDown() {
        cn.crabc.core.app.util.UserThreadLocal.remove();
    }

    @Test
    @DisplayName("测连：驱动层拿到明文密码，入参对象保持 Base64 不被改写")
    void testConnectionUsesPlainPasswordWithoutMutatingArgument() {
        BaseDatasource dataSource = datasource();

        service.test(dataSource);

        ArgumentCaptor<BaseDataSource> probe = ArgumentCaptor.forClass(BaseDataSource.class);
        verify(iBaseDataService).testConnection(probe.capture());
        assertEquals(PLAIN, probe.getValue().getPassword(), "建连必须用明文，否则驱动认证失败");
        assertEquals(ENCODED, dataSource.getPassword(), "把明文写回入参会让后续落库存入明文");
    }

    @Test
    @DisplayName("先测连再新增：落库仍是 Base64，连接池拿到明文")
    void createAfterTestStillStoresEncodedPassword() {
        when(iBaseDataService.testConnection(org.mockito.ArgumentMatchers.any())).thenReturn("连接成功");
        BaseDatasource dataSource = datasource();

        service.test(dataSource);
        service.addDataSource(dataSource);

        ArgumentCaptor<BaseDatasource> inserted = ArgumentCaptor.forClass(BaseDatasource.class);
        verify(dataSourceMapper).insertDataSource(inserted.capture());
        assertEquals(ENCODED, inserted.getValue().getPassword());

        ArgumentCaptor<BaseDataSource> pooled = ArgumentCaptor.forClass(BaseDataSource.class);
        verify(dataSourceManager).createDataSource(pooled.capture());
        assertEquals(PLAIN, pooled.getValue().getPassword());
    }

    private BaseDatasource datasource() {
        BaseDatasource dataSource = new BaseDatasource();
        dataSource.setDatasourceName("回归数据源");
        dataSource.setDatasourceType("mysql");
        dataSource.setJdbcUrl("jdbc:mysql://127.0.0.1:3306/apigo?useSSL=false");
        dataSource.setUsername("root");
        dataSource.setPassword(ENCODED);
        return dataSource;
    }
}
