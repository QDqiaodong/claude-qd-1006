package com.waterworks.plant.common;

/**
 * 并发或状态冲突（HTTP 409）。与普通参数错误区分，便于失败方读取“已被谁接管”等结果。
 */
public class ConflictException extends RuntimeException {

    private final transient Object data;

    public ConflictException(String message) {
        this(message, null);
    }

    public ConflictException(String message, Object data) {
        super(message);
        this.data = data;
    }

    public Object getData() {
        return data;
    }
}
