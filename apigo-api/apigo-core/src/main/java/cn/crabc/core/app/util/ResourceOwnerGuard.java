package cn.crabc.core.app.util;

import cn.crabc.core.app.entity.BaseApiInfo;
import cn.crabc.core.app.entity.BaseApp;
import cn.crabc.core.app.entity.BaseDatasource;
import cn.crabc.core.app.entity.BaseGroup;
import cn.crabc.core.app.mapper.BaseApiInfoMapper;
import cn.crabc.core.app.mapper.BaseAppMapper;
import cn.crabc.core.app.mapper.BaseDataSourceMapper;
import cn.crabc.core.app.mapper.BaseGroupMapper;
import cn.crabc.core.datasource.enums.ErrorStatusEnum;
import cn.crabc.core.datasource.exception.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 资源归属校验（数据权限）。
 * <p>
 * 平台是多租户自助形态：任意注册用户都能创建接口、数据源与应用，因此管理端每个
 * "按 id 操作"的入口都必须先确认资源归属，否则就是横向越权（IDOR）。
 * <p>
 * 约定：
 * <ul>
 *     <li>管理员（{@link UserThreadLocal#isAdmin()}）可跨租户操作，校验直接放行；</li>
 *     <li>普通用户只能操作 {@code create_by} 等于自己的资源；</li>
 *     <li>历史数据 {@code create_by} 为空时，仅管理员可操作（fail-closed）。</li>
 * </ul>
 * 校验放在 service 层而不是仅在 mapper 上追加 {@code create_by} 条件：这样
 * "资源不存在"与"无权操作"能给出不同的状态码，也便于新增接口时统一复用。
 *
 * @author yuqf
 */
@Component
public class ResourceOwnerGuard {

    @Autowired
    private BaseApiInfoMapper apiInfoMapper;
    @Autowired
    private BaseAppMapper baseAppMapper;
    @Autowired
    private BaseDataSourceMapper dataSourceMapper;
    @Autowired
    private BaseGroupMapper baseGroupMapper;

    /**
     * 数据范围过滤值：管理员返回 null（不过滤），普通用户返回自己的 userId。
     * <p>
     * 供列表类查询使用，配合 mapper 中的 {@code <if test="createBy != null">} 生效。
     *
     * @return 归属过滤值，管理员为 null
     */
    public String ownerScope() {
        return isAdmin() ? null : UserThreadLocal.getUserId();
    }

    /**
     * 当前用户是否可跨租户操作。
     */
    public boolean isAdmin() {
        return UserThreadLocal.isAdmin();
    }

    /**
     * 校验接口归属并返回接口实体。
     *
     * @param apiId 接口ID
     * @return 接口实体（存在）
     */
    public BaseApiInfo assertApi(Long apiId) {
        if (apiId == null) {
            throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), "接口ID不能为空");
        }
        BaseApiInfo apiInfo = apiInfoMapper.selectApiById(apiId);
        if (apiInfo == null) {
            throw new CustomException(ErrorStatusEnum.API_NOT_FOUNT.getCode(), ErrorStatusEnum.API_NOT_FOUNT.getMassage());
        }
        assertOwner(apiInfo.getCreateBy(), "接口");
        return apiInfo;
    }

    /**
     * 校验应用归属并返回应用实体。
     *
     * @param appId 应用ID
     * @return 应用实体（存在）
     */
    public BaseApp assertApp(Long appId) {
        if (appId == null) {
            throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), "应用ID不能为空");
        }
        BaseApp app = baseAppMapper.selectOne(appId);
        if (app == null) {
            throw new CustomException(ErrorStatusEnum.APP_NOT_FOUNT.getCode(), ErrorStatusEnum.APP_NOT_FOUNT.getMassage());
        }
        assertOwner(app.getCreateBy(), "应用");
        return app;
    }

    /**
     * 校验数据源归属并返回数据源实体。
     *
     * @param datasourceId 数据源ID
     * @return 数据源实体（存在）
     */
    public BaseDatasource assertDataSource(Integer datasourceId) {
        if (datasourceId == null) {
            throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), "数据源ID不能为空");
        }
        BaseDatasource dataSource = dataSourceMapper.selectOne(datasourceId);
        if (dataSource == null) {
            throw new CustomException(ErrorStatusEnum.DATASOURCE_NOT_FOUNT.getCode(),
                    ErrorStatusEnum.DATASOURCE_NOT_FOUNT.getMassage());
        }
        assertOwner(dataSource.getCreateBy(), "数据源");
        return dataSource;
    }

    /**
     * 校验分组归属。
     *
     * @param groupId 分组ID
     */
    public void assertGroup(Integer groupId) {
        if (groupId == null) {
            return;
        }
        BaseGroup group = baseGroupMapper.selectOne(groupId);
        if (group == null) {
            throw new CustomException(ErrorStatusEnum.PARAM_NOT_FOUNT.getCode(), "分组不存在");
        }
        assertOwner(group.getCreateBy(), "分组");
    }

    /**
     * 批量校验接口归属：全部属于当前用户且已发布。
     * <p>
     * 用于"应用-接口授权"绑定场景，避免用户把自己的应用绑到他人接口上，或把他人接口挂进自己的应用。
     *
     * @param apiIds 待绑定的接口ID集合
     */
    public void assertApisOwnedAndReleased(Collection<Long> apiIds) {
        if (apiIds == null || apiIds.isEmpty()) {
            return;
        }
        Set<Long> distinct = new LinkedHashSet<>(apiIds);
        distinct.remove(null);
        if (distinct.isEmpty()) {
            return;
        }
        String owner = UserThreadLocal.getUserId();
        Integer owned = apiInfoMapper.countOwnedReleasedApis(distinct, owner);
        if (owned == null || owned != distinct.size()) {
            throw new CustomException(ErrorStatusEnum.FORBID_OPERATE.getCode(),
                    "只能为已发布的自有接口授权，请检查所选接口的归属与发布状态");
        }
    }

    /**
     * 归属判定：管理员放行，其余必须 create_by 与当前用户一致。
     *
     * @param createBy 资源创建人
     * @param type     资源类型（仅用于错误提示）
     */
    public void assertOwner(String createBy, String type) {
        if (isAdmin()) {
            return;
        }
        String userId = UserThreadLocal.getUserId();
        if (createBy == null || createBy.isBlank() || !createBy.equals(userId)) {
            throw new CustomException(ErrorStatusEnum.FORBID_OPERATE.getCode(),
                    "无权操作他人的" + type + "，如需跨租户操作请联系管理员");
        }
    }
}
