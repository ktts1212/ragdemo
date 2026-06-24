package com.example.ragdemo.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Result<T> {
    private int code;
    private String message;
    private T data;

    public static <T> Result<T> success(T data) {
        return new Result<>(200,"success",data);
    }

    public static <T> Result<T> success() {
        return new Result<>(200,"success",null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code,message,null);
    }

    public static <T> Result<T> fail(String message) {
        return fail(500,message);
    }
}