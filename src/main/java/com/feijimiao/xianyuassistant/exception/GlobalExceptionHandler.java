package com.feijimiao.xianyuassistant.exception;

import com.feijimiao.xianyuassistant.common.ResultObject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常拦截器
 * 捕获所有异常并返回统一格式的错误信息
 *
 * 业务码契约（前端 request.ts 拦截器约定）：
 * - 401 仅表示控制台登录态失效（会清 token 跳登录页），业务异常禁止使用 401；
 * - 1001 表示平台滑块验证码，前端透传给业务层展示验证引导；
 * - 1002 表示请求参数格式问题。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 参数校验失败的统一业务码（Bad Request 语义）
     */
    private static final int CODE_BAD_REQUEST = 400;

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public ResultObject<Void> handleBusinessException(BusinessException e) {
        log.warn("[BusinessException] code={}, msg={}", e.getCode(), e.getMessage());
        return ResultObject.failed(e.getCode(), e.getMessage());
    }

    /**
     * 处理验证码异常：必须返回 1001（滑块验证码契约）。
     * 不能返回 401——前端拦截器把 401 视为登录态失效并强制登出，
     * 会把"平台要求滑块验证"误变成"控制台被登出"。
     */
    @ExceptionHandler(CaptchaRequiredException.class)
    public ResultObject<Map<String, Object>> handleCaptchaRequiredException(CaptchaRequiredException e) {
        log.warn("[CaptchaRequired] msg={}, captchaUrlPresent={}", e.getMessage(), e.getCaptchaUrl() != null);
        // JSON 形状与 WebSocketController 的 CaptchaInfoDTO 保持一致（needCaptcha/captchaUrl/message），
        // 前端按同一契约渲染验证引导
        Map<String, Object> captchaInfo = new HashMap<>();
        captchaInfo.put("needCaptcha", true);
        captchaInfo.put("captchaUrl", e.getCaptchaUrl());
        captchaInfo.put("message", e.getMessage());
        return new ResultObject<>(1001, "需要滑块验证", captchaInfo);
    }

    /**
     * 处理 @RequestBody 校验失败（@Valid 触发）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResultObject<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("[ValidException] {}", msg);
        return ResultObject.failed(CODE_BAD_REQUEST, msg);
    }

    /**
     * 处理表单绑定校验失败
     */
    @ExceptionHandler(BindException.class)
    public ResultObject<Void> handleBindException(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("[BindException] {}", msg);
        return ResultObject.failed(CODE_BAD_REQUEST, msg);
    }

    /**
     * 处理 @Validated 单参数校验失败
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResultObject<Void> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.warn("[ConstraintViolation] {}", msg);
        return ResultObject.failed(CODE_BAD_REQUEST, msg);
    }

    /**
     * 处理缺少必填参数
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResultObject<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("[MissingParam] {}", e.getParameterName());
        return ResultObject.failed(1002, "缺少必填参数: " + e.getParameterName());
    }

    /**
     * 处理请求体格式错误
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResultObject<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("[NotReadable] {}", e.getMessage());
        return ResultObject.failed(1002, "请求体格式错误");
    }

    /**
     * 处理不支持的 HTTP 方法
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResultObject<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("[MethodNotSupported] {}", e.getMethod());
        return ResultObject.failed(500, "不支持的请求方法: " + e.getMethod());
    }

    /**
     * 处理所有未捕获的异常
     */
    @ExceptionHandler(Exception.class)
    public ResultObject<Void> handleException(Exception e) {
        log.error("[UnexpectedException]", e);
        return ResultObject.failed(500, "系统繁忙，请稍后再试");
    }
}
