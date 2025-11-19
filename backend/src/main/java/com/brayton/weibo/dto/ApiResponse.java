package com.brayton.weibo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;
    private LocalDateTime timestamp;

    // 成功返回
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data, LocalDateTime.now());
    }
}
