package cn.crabc.core.app.controller;

import cn.crabc.core.app.entity.BaseUser;
import cn.crabc.core.app.entity.param.UserParam;
import cn.crabc.core.app.service.system.IBaseUserService;
import cn.crabc.core.app.util.*;
import com.github.benmanes.caffeine.cache.Cache;
import org.apache.commons.codec.binary.Base64;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/box/sys/user")
public class SysUserController {


    @Autowired
    private IBaseUserService iBaseUserService;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    @Qualifier("dataCache")
    Cache<String, Object> caffeineCache;
    @Autowired
    private JsonMapper jsonMapper;
    /**
     * 登录
     *
     * @return
     */
    @PostMapping("/login")
    public Result login(@RequestBody UserParam UserParam) {
        BaseUser userInfo = iBaseUserService.getUserByName(UserParam.getUsername());
        if (userInfo == null) {
            return Result.error("账号或密码错误!");
        }
        String pwd = new String(Base64.decodeBase64(UserParam.getPassword()), StandardCharsets.UTF_8);
        String md5 = Md5Utils.hash(pwd).toUpperCase();
        if (!userInfo.getPassword().equals(md5)) {
            return Result.error("账号或密码错误!");
        }
        Map<String, Object> user = new HashMap<>();
        String token = jwtUtil.createToken(userInfo.getUserId(), userInfo.getUsername());
        if (token == null) {
            return Result.error("登录失败");
        }
        user.put("expires", jwtUtil.getExpireSeconds());
        user.put("access_token", token);
        user.put("refresh_token", UUID.randomUUID().toString().replace("-", ""));
        return Result.success(user);
    }

    /**
     * 注销
     * <p>
     * 仅清理本次请求的线程上下文。令牌为无状态凭证，服务端未做吊销，
     * 注销后旧令牌在有效期内仍可继续使用（如需立即失效需引入服务端会话/黑名单）。
     *
     * @return
     */
    @PostMapping("/loginout")
    public Result loginout() {
        return Result.success();
    }

    /**
     * 当前用户的基本信息
     *
     * @return
     */
    @GetMapping("/info")
    public Result userInfo() {
        BaseUser userInfo = iBaseUserService.getUserById(Long.parseLong(UserContext.getUserId()));
        return Result.success(userInfo);
    }
    /**
     * 用户菜单
     * @return
     */
    @GetMapping("/menus")
    public Result userMenus(){
        // 固定菜单
        String menus ="[{\"name\":\"API Hub\",\"checked\":null,\"id\":2,\"parentId\":1,\"open\":null,\"path\":null,\"url\":\"/hub\",\"sort\":2,\"menuType\":1,\"code\":\"Home\",\"hidden\":false,\"permission\":\"/api/box/sys/user/info;/api/box/sys/user/menus;/api/box/sys/message/**;/api/box/sys/user/update/pwd\",\"subMenus\":[{\"name\":\"接口详情\",\"checked\":null,\"id\":7,\"parentId\":2,\"open\":null,\"path\":null,\"url\":\"/doc\",\"sort\":6,\"menuType\":1,\"code\":\"DataCatelogApiDetails\",\"hidden\":true,\"permission\":\"doc\",\"subMenus\":[]}]},{\"name\":\"工作台\",\"checked\":null,\"id\":3,\"parentId\":1,\"open\":null,\"path\":null,\"url\":\"/working\",\"sort\":3,\"menuType\":1,\"code\":\"DataService\",\"hidden\":false,\"permission\":\"/api/box/sys/common/**;/api/box/sys/api/info/**;/api/box/sys/metadata/**;/api/box/sys/test/**;/api/box/sys/group/**;/api/box/sys/user/check/name\",\"subMenus\":[]},{\"name\":\"接口管理\",\"checked\":null,\"id\":4,\"parentId\":1,\"open\":null,\"path\":null,\"url\":\"/manage\",\"sort\":4,\"menuType\":1,\"code\":\"Manage\",\"hidden\":false,\"permission\":\"manage\",\"subMenus\":[{\"name\":\"应用管理\",\"checked\":null,\"id\":8,\"parentId\":4,\"open\":null,\"path\":null,\"url\":\"/manage/keys\",\"sort\":1,\"menuType\":1,\"code\":\"Application\",\"hidden\":true,\"permission\":\"/api/box/sys/app/**\",\"subMenus\":[]},{\"name\":\"接口列表\",\"checked\":null,\"id\":9,\"parentId\":4,\"open\":null,\"path\":null,\"url\":\"/manage/apis\",\"sort\":1,\"menuType\":1,\"code\":\"MyApi\",\"hidden\":true,\"permission\":\"/api/box/sys/api/info/**\",\"subMenus\":[]},{\"name\":\"指标统计\",\"checked\":null,\"id\":40,\"parentId\":4,\"open\":null,\"path\":null,\"url\":\"/manage/monitoring\",\"sort\":1,\"menuType\":1,\"code\":\"Monitoring\",\"hidden\":true,\"permission\":\"/api/box/sys/api/log/**\",\"subMenus\":[]},{\"name\":\"日志管理\",\"checked\":null,\"id\":12,\"parentId\":4,\"open\":null,\"path\":null,\"url\":\"/manage/logs\",\"sort\":1,\"menuType\":1,\"code\":\"UseLog\",\"hidden\":true,\"permission\":\"/api/box/sys/api/log/**\",\"subMenus\":[{\"name\":\"日志列表\",\"checked\":null,\"id\":13,\"parentId\":12,\"open\":null,\"path\":null,\"url\":\"/manage/logs/list\",\"sort\":1,\"menuType\":1,\"code\":\"UseLogList\",\"hidden\":true,\"permission\":\"/api/box/sys/api/log/**\",\"subMenus\":[]},{\"name\":\"日志详情\",\"checked\":null,\"id\":14,\"parentId\":12,\"open\":null,\"path\":null,\"url\":\"/manage/logs/detail\",\"sort\":1,\"menuType\":1,\"code\":\"DetailUesLog\",\"hidden\":true,\"permission\":null,\"subMenus\":[]}]},{\"name\":\"分类管理\",\"checked\":null,\"id\":30,\"parentId\":4,\"open\":null,\"path\":null,\"url\":\"/manage/classification\",\"sort\":1,\"menuType\":1,\"code\":\"Classification\",\"hidden\":true,\"permission\":\"manage:classification\",\"subMenus\":[]},{\"name\":\"数据源\",\"checked\":null,\"id\":31,\"parentId\":4,\"open\":null,\"path\":null,\"url\":\"/manage/datasource\",\"sort\":1,\"menuType\":1,\"code\":\"DataSource\",\"hidden\":true,\"permission\":\"/api/box/sys/datasource/**;/api/box/sys/user/pubKey\",\"subMenus\":[]},{\"checked\":null,\"code\":\"datasource\",\"hidden\":false,\"id\":33,\"menuType\":1,\"name\":\"数据源\",\"open\":null,\"parentId\":1,\"path\":null,\"permission\":\"/api/box/sys/datasource/**;/api/box/sys/user/pubKey\",\"sort\":4,\"subMenus\":[],\"url\":\"/datasource\"},{\"name\":\"密码重置\",\"checked\":null,\"id\":32,\"parentId\":4,\"open\":null,\"path\":null,\"url\":\"/manage/edit-pwd\",\"sort\":1,\"menuType\":1,\"code\":\"ChangePassword\",\"hidden\":true,\"permission\":\"/api/box/sys/user/update/pwd\",\"subMenus\":[]}]}]";
        List<Map<String, Object>> list = jsonMapper.readValue(menus, List.class);
        return Result.success(list);
    }

    /**
     * 修改密码
     * @param user
     * @return
     */
    @PostMapping("/reset/pwd")
    public Result resetPassword(@RequestBody UserParam user){
        BaseUser userInfo = iBaseUserService.getUserById(Long.valueOf(UserContext.getUserId()));
        if (userInfo == null) {
            return Result.error("账号不存在!");
        }
        String pwd = new String(Base64.decodeBase64(user.getPassword()), StandardCharsets.UTF_8);
        String md5Pwd = Md5Utils.hash(pwd).toUpperCase();
        if (!userInfo.getPassword().equals(md5Pwd)) {
            return Result.error("原密码错误!");
        }
        String newPwd = new String(Base64.decodeBase64(user.getNewPassword()), StandardCharsets.UTF_8);
        String newMd5Pwd = Md5Utils.hash(newPwd).toUpperCase();
        userInfo.setPassword(newMd5Pwd);
        iBaseUserService.updateUser(userInfo);
        return Result.success();
    }

    /**
     * 用户注册
     * @param user
     * @return
     */
    @PostMapping("/register")
    public Result register(@RequestBody UserParam user){
        String pwd = new String(Base64.decodeBase64(user.getPassword()), StandardCharsets.UTF_8);
        String md5 = Md5Utils.hash(pwd).toUpperCase();
        user.setPassword(md5);
        BaseUser baseUser = new BaseUser();
        BeanUtils.copyProperties(user, baseUser);
        iBaseUserService.addUser(baseUser);
        return Result.success();
    }

    /**
     * 获取公钥
     *
     * @return
     * @throws Exception
     */
    @GetMapping("/pubKey")
    public Result getPublicKey() throws Exception {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        RSAUtils.RSAKeyPair rsaKeyPair = RSAUtils.getKey();
        caffeineCache.put("priKey_" + UserContext.getUserId(), rsaKeyPair.getPrivateKey());
        Map<String, String> map = new HashMap(3);
        map.put("public", rsaKeyPair.getPublicKey());
        map.put("key", uuid);
        return Result.success(map);
    }
}
