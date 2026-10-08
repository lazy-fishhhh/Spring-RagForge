package com.springragforge.common;

/**
 * 统一响应体。前端按 success 字段分流：true 取 data，false 提示 message。
 * 不用 HTTP 状态码区分业务错误，省得前端两套判断逻辑。
 */
public class Result<T> {

    private final boolean success;
    private final String message;
    private final T data;

    public Result(boolean success, String message, T data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(true, "ok", data);
    }

    public static Result<Void> ok() {
        return new Result<>(true, "ok", null);
    }

    public static <T> Result<T> fail(String message) {
        return new Result<>(false, message, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }
}
