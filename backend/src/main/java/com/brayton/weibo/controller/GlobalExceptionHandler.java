package com.brayton.weibo.controller;

import com.brayton.weibo.dto.ErrorResponse;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.WeiboException;
import com.brayton.weibo.ratelimit.RateLimitedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.time.LocalDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(WeiboException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(
            WeiboException ex, HttpServletRequest request) {

        ErrorResponse error = new ErrorResponse(
                ex.getCode(),
                ex.getMessage(),
                LocalDateTime.now(),
                request.getRequestURI()
        );

        return new ResponseEntity<>(error, HttpStatus.OK);
    }


    /** 限流用真正的 429：客户端和网关都认，Retry-After 告诉它多久后再来；其余业务错误仍按惯例回 200 + code */
    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ErrorResponse> handleRateLimited(RateLimitedException ex, HttpServletRequest request) {
        ErrorResponse error = new ErrorResponse(
                CommonErrorCode.RATE_LIMITED.getCode(),
                CommonErrorCode.RATE_LIMITED.getMessage(),
                LocalDateTime.now(),
                request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
                .body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "invalid parameter";

        ErrorResponse error = new ErrorResponse(
                CommonErrorCode.VALIDATION_FAILED.getCode(),
                message,
                LocalDateTime.now(),
                request.getRequestURI()
        );

        return new ResponseEntity<>(error, HttpStatus.OK);
    }

}
