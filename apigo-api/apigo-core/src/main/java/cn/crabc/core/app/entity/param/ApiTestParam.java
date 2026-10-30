package cn.crabc.core.app.entity.param;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * API测试参数，承载测试弹窗传入的SQL、数据源和运行时参数。
 */
@Setter
@Getter
public class ApiTestParam {

    /**
     * 接口ID，运行预览时用于读取接口已保存的脚本超时等配置
     */
    private Long apiId;

    /**
     * 页面当前设置的脚本超时时间（秒），未保存的修改也能在运行预览/测试时立即生效
     */
    private Integer scriptTimeoutSeconds;

    private String sqlScript;

    private String datasourceId;

    private String datasourceType;

    private String schemaName;

    private String sqlParams;

    private Integer pageSetup;

    /**
     * 是否开启事务，1 开启、0 关闭；仅测试多脚本DML时传给执行层
     */
    private Integer transactionEnabled;

    private String resultType;

    private Object requestParams;

    /**
     * 请求体参数，页面测试弹窗传入：可能是 JSON 字符串（"{\"list\":[1,2]}"），也可能是已反序列化的对象
     */
    private Object bodyData;

    private Map<String,Object> queryParam;

    /**
     * 数据插件配置（开发页 baseInfo.pluginConfig）：兼容 JSON 字符串与已反序列化对象，由控制层统一归一化
     */
    private Object pluginConfig;
}
