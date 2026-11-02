package cn.crabc.core.app.controller;

import cn.crabc.core.app.entity.BaseGroup;
import cn.crabc.core.app.entity.vo.BaseGroupVO;
import cn.crabc.core.app.service.system.IBaseGroupService;
import cn.crabc.core.app.util.Result;
import cn.crabc.core.app.util.UserThreadLocal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * api分组
 */

@RestController
@RequestMapping("/api/box/sys/group")
public class BaseGroupController {

    @Autowired
    private IBaseGroupService iBaseGroupService;

    /**
     * 分组树
     * @return
     */
    @GetMapping("/tree")
    public Result groupTree(Long apiId){
        // 分组树沿用"全部分组可见"的既有语义：默认分组由平台共享，
        // 写操作（改/删）已按 create_by 做归属校验。
        List<BaseGroupVO> baseGroups = iBaseGroupService.groupTree(UserThreadLocal.getUserId(), apiId);
        return Result.success(baseGroups);
    }

    /**
     * 新增分组
     * @param baseGroup
     * @return
     */
    @PostMapping
    public Result addGroup(@RequestBody BaseGroup baseGroup){
        baseGroup.setCreateBy(UserThreadLocal.getUserId());
        iBaseGroupService.addGroup(baseGroup);
        return Result.success();
    }

    /**
     * 编辑分组
     * @param baseGroup
     * @return
     */
    @PutMapping
    public Result updateGroup(@RequestBody BaseGroup baseGroup){
        iBaseGroupService.updateGroup(baseGroup,UserThreadLocal.getUserId());
        return Result.success();
    }

    /**
     * 删除分组
     * @param groupId
     * @return
     */
    @DeleteMapping("/{groupId}")
    public Result deleteGroup(@PathVariable Integer groupId){
        iBaseGroupService.delteGroup(groupId, UserThreadLocal.getUserId());
        return Result.success();
    }
}
