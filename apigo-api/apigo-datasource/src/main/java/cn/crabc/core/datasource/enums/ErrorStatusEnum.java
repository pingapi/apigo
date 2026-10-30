package cn.crabc.core.datasource.enums;

/**
 * 错误状态枚举
 *
 * @author yuqf
 */
public enum ErrorStatusEnum {
    SYSTEM_ERROR( 50001, "系统繁忙，请稍后重试"),
    SYSTEM_UPGRADE( 50002, "系统升级中"),
    API_INVALID( 40001, "无效的接口"),
    API_UN_AUTH( 40002, "无访问接口权限"),
    API_EXPIRE( 40003, "接口授权已过期"),
    API_LIMIT(40004, "接口已被限流"),
    API_FUSING(40005, "接口已被熔断"),
    API_OFFLINE(40006, "接口已下线"),
    API_COUNT_OUT(40007, "接口调用次数已达上线"),
    APP_UN_AUTH(40008, "认证参数X-AppKey不能为空"),
    IP_INVALID( 40010, "IP地址不在有效范围内"),
    API_SQL_ERROR( 40011, "SQL执行失败，请检查SQL是否正常"),
    API_SCRIPT_TIMEOUT( 40012, "脚本执行超时"),
    API_AUTH_ERROR( 40013, "认证失败，请检查认证参数是否正确"),
    PARAM_NOT_FOUNT( 41000, "必传参数不能为空"),
    JWT_UN_AUTH( 402, "用户未登录"),
    JWT_LOGIN_EXPIRE( 401, "登录失效，请重新登录"),
    SHA_PARAM_NOT_FOUNT( 41006, "认证参数(X-AppKey/X-Timestamp/X-Sign)不能为空"),
    SHA_APPKEY_NOT_FOUNT( 41007, "认证参数X-AppKey不能为空"),
    SHA_TIMESTAMP_EXPIRE( 41008, "认证参数timestamp失效"),
    FORBID_OPERATE(44000, "无效操作"),
    API_NOT_FOUNT(44001, "无效的API"),
    API_NOT_OPERATE(44002, "API已发布上线，请先下线后在进行编辑"),
    USER_REPEAT(44002, "用户名已存在"),
    DATASOURCE_NOT_FOUNT(44003, "无效的数据源"),
    APP_NOT_FOUNT(44004, "无效的应用");


    private int code;
    private String massage;

    ErrorStatusEnum(Integer code, String massage) {
        this.code = code;
        this.massage = massage;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMassage() {
        return massage;
    }

    public void setMassage(String massage) {
        this.massage = massage;
    }
}
