package cn.crabc.core.app.mapper;


import cn.crabc.core.app.entity.BaseDatasource;
import cn.crabc.core.spi.bean.BaseDataSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 数据源 Mapper接口
 *
 * @author yuqf
 */
@Mapper
public interface BaseDataSourceMapper {

    List<BaseDataSource> list();

    /**
     *
     * 数据源列表
     * @param dataSourceName 名称关键字
     * @param createBy 归属过滤，null 表示不限（管理员）
     * @return
     */
    List<BaseDatasource> selectList(@Param("dataSourceName") String dataSourceName,
                                    @Param("createBy") String createBy);
    /**
     * 查询数据源详情
     * @param datasourceId
     * @return
     */
    BaseDatasource selectOne(Integer datasourceId);

    /**
     *  插入数据源
     * @param dataSource
     * @return
     */
    Integer insertDataSource(BaseDatasource dataSource);

    /**
     * 更新数据源
     * @param dataSource
     * @return
     */
    Integer updateDataSource(BaseDatasource dataSource);

    /**
     * 删除数据源
     * @param datasourceId
     * @return
     */
    Integer deleteDataSource(Integer datasourceId);
}
