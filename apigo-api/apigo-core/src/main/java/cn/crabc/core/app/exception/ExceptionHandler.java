package cn.crabc.core.app.exception;

import cn.crabc.core.app.util.Result;
import cn.crabc.core.datasource.exception.CustomException;
import cn.crabc.core.plugin.DataPluginException;
import cn.crabc.core.spi.PluginException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;


/**
 * 异常统一返回处理
 *
 * @author yuqf
 */
@RestControllerAdvice
public class ExceptionHandler {
    private final static Logger log = LoggerFactory.getLogger(ExceptionHandler.class);

    /**
     * 处理自定义异常
     *
     * @param e
     * @return
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(value = CustomException.class)
    @ResponseBody
    public Result custom(CustomException e) {
        return Result.error(e.getCode(), e.getMsg());
    }

    /**
     * 处理插件异常
     *
     * @param e
     * @return
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(value = PluginException.class)
    @ResponseBody
    public Result custom(PluginException e) {
        return Result.error(e.getCode(), e.getMsg());
    }

    /**
     * 处理数据插件异常
     * <p>
     * 对外只返回泛化原因（含出错插件标识），不携带任何行数据值；详细堆栈已由执行器写入日志。
     *
     * @param e 数据插件异常
     * @return 统一错误响应
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(value = DataPluginException.class)
    @ResponseBody
    public Result dataPlugin(DataPluginException e) {
        String pluginKey = e.getPluginKey();
        String message = pluginKey == null
                ? "数据插件配置不可用，请联系管理员"
                : "数据插件[" + pluginKey + "]执行失败，请检查插件配置";
        return Result.error(50020, message);
    }

    /**
     * 处理空指针的异常
     *
     * @param req
     * @param e
     * @return
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(value = NullPointerException.class)
    @ResponseBody
    public Result exceptionHandler(HttpServletRequest req, NullPointerException e) {
        log.error("空指针异常，请求地址：{}", req.getRequestURI(), e);
        return Result.error(50001, "必传参数不能为空！");
    }

    /**
     * 运行时异常
     * @param req
     * @param e
     * @return
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(value = RuntimeException.class)
    @ResponseBody
    public Result exceptionHandler(HttpServletRequest req, RuntimeException e) {
        // 泛化对外文案：原始报错可能包含 host/port/表名等敏感信息，细节只进日志，用 traceId 关联排查
        String traceId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        log.error("执行异常，请求地址：{}，traceId：{}", req.getRequestURI(), traceId, e);
        return Result.error(50005, "系统繁忙，请稍后重试（traceId：" + traceId + "）");
    }

    /**
     * 处理参数校验异常 --Json 转换异常
     *
     * @param req
     * @param e
     * @return
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(value = HttpMessageNotReadableException.class)
    @ResponseBody
    public Result exceptionHandler(HttpServletRequest req, HttpMessageNotReadableException e) {
        e.printStackTrace();
        return Result.error(50002, "Body参数异常，请检查Body/json是否正确传参");
    }
}
