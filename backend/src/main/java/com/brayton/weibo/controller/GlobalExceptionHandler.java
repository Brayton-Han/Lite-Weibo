package com.brayton.weibo.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> handleRuntimeException(RuntimeException e) {
        return ResponseEntity
                .badRequest()
                .body(e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationExceptions(MethodArgumentNotValidException ex) {

        // 1. 创建一个 Map 来存储所有字段的错误信息
        Map<String, String> errors = new HashMap<>();

        // 2. 遍历所有字段错误，提取字段名和默认错误消息
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();

            // 存储字段名和错误信息
            errors.put(fieldName, errorMessage);
        });

        // 3. 返回 HTTP 400 Bad Request 和包含所有错误的 Map
        return new ResponseEntity<>(errors, HttpStatus.BAD_REQUEST);
    }
}
