package cn.crabc.core.app.mapper;

import cn.crabc.core.app.entity.BaseApiInfo;
import cn.crabc.core.app.entity.dto.ApiInfoDTO;
import cn.crabc.core.app.entity.vo.ApiComboBoxVO;
import cn.crabc.core.app.entity.vo.BaseApiInfoVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * API基本信息 Mapper接口
 *
 * @author yuqf
 */
@Mapper
public interface BaseApiInfoMapper {

    /**
     * API列表
     *
     * @param apiName 名称/路径关键字
     * @param devType 状态
     * @param createBy 归属过滤，null 表示不限（管理员）
     * @return
     */
    List<BaseApiInfo> selectList(@Param("apiName") String apiName, @Param("devType") String devType,
                                 @Param("createBy") String createBy);

    /**
     * 统计属于指定用户且已发布的接口数量，用于"应用-接口授权"绑定前的归属校验。
     *
     * @param apiIds 接口ID集合
     * @param userId 归属用户
     * @return 命中数量
     */
    Integer countOwnedReleasedApis(@Param("apiIds") java.util.Collection<Long> apiIds, @Param("userId") String userId);

    /**
     * 分组查询API信息
     *
     * @param groupId
     * @return
     */
    List<ApiComboBoxVO> selectApiGroup(@Param("groupId") Integer groupId, @Param("userId") String userId);

    /**
     * 查询关联应用的API
     *
     * @param appId
     * @return
     */
    List<ApiComboBoxVO> selectApiApp(Long appId);

    /**
     * 根据Id查询基本信息
     *
     * @param apiId
     * @return
     */
    BaseApiInfo selectApiById(Long apiId);

    /**
     * 根据Id查询api基本信息
     *
     * @param apiId
     * @return
     */
    BaseApiInfoVO selectBaseApi(Long apiId);

    /**
     * 校验api地址是否已经存在
     *
     * @param apiPath
     * @param method
     * @return
     */
    Integer checkApiPath(@Param("apiId") Long apiId, @Param("apiPath") String apiPath, @Param("method") String method);


    /**
     * 根据apiId查询信息
     * @param apiId
     * @return
     */
    List<ApiInfoDTO>  selectApiDetail(@Param("apiId") Long apiId);

    /**
     * 根据apiId查询信息
     * @param
     * @return
     */
    ApiInfoDTO getApiDetail(@Param("method") String method, @Param("apiPath") String apiPath);

    /**
     * 插入API
     *
     * @param apiInfo
     * @return
     */
    Integer insertApiInfo(BaseApiInfo apiInfo);

    /**
     * 编辑API信息
     *
     * @param apiInfo
     * @return
     */
    Integer updateApiInfo(BaseApiInfo apiInfo);

    /**
     * 更新API状态
     *
     * @param apiInfo
     * @return
     */
    Integer updateApiState(BaseApiInfo apiInfo);

    /**
     * 更新API限流配置，窗口和次数同时为空表示清空限流。
     *
     * @param apiInfo API限流字段
     * @return 影响行数
     */
    Integer updateRateLimit(BaseApiInfo apiInfo);

    /**
     * 更新API暂存内容
     *
     * @param apiId
     * @param draftContent
     * @param updateBy
     * @return
     */
    Integer updateDraftContent(@Param("apiId") Long apiId, @Param("draftContent") String draftContent, @Param("updateBy") String updateBy);

    /**
     * 删除API
     * @param apiId
     * @return
     */
    Integer deleteApiInfo(@Param("apiId") Long apiId,@Param("userId") String userId);

    /**
     * API校验统计
     *
     * @param apiId
     * @return
     */
    Integer countApi(Long apiId);

}
