package com.springragforge.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 兜底异常处理。业务里主动返回 Result.fail 即可，这里只拦没接住的异常，
 * 避免把堆栈直接抛给前端。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public Result<Void> handle(Exception e) {
        log.error("未处理的异常", e);
        return Result.fail("服务器内部错误：" + e.getMessage());
    }
}
