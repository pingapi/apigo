package cn.crabc.core.datasource.exception;

/**
 * 自定义异常结构
 *
 * @author yuqf
 */
public class CustomException extends RuntimeException {

    private int code;
    private String msg;

    public CustomException(int code, String message) {
        // message 必须同时进入 Throwable：日志、MCP 框架回传给模型的错误文本都按 getMessage() 取值
        super(message);
        this.code = code;
        this.msg = message;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
}
