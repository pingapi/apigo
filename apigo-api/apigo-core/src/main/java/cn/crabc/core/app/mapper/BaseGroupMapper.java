package cn.crabc.core.app.mapper;

import cn.crabc.core.app.entity.BaseGroup;
import cn.crabc.core.app.entity.vo.BaseGroupVO;
import cn.crabc.core.app.entity.vo.GroupApiVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * api分组 接口
 *
 * @author yuqf
 */
@Mapper
public interface BaseGroupMapper {

    /**
     * 新增分组
     */
    Integer insert(BaseGroup group);

    /**
     * 编辑分组
     */
    Integer update(BaseGroup group);

    /**
     * 删除分组
     * @param groupId
     */
    Integer delete(Integer groupId);

    /**
     * 删除用户分组
     * @param userId
     */
    Integer deleteAll(String userId);

    /**
     * 查询分组列表
     * @param userId
     */
    List<BaseGroupVO> selectList(@Param("userId") String userId);

    /**
     * 查询详情
     * @param groupId
     */

    BaseGroup selectOne(Integer groupId);

    /**
     * 获取分组下API
     *
     * @return
     */
    List<GroupApiVO> selectGroupApi();
}
