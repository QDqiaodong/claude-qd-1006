package com.waterworks.plant.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RegisterDosingRequest(
        @NotBlank(message = "药剂不能为空") String chemName,
        @NotNull(message = "投加量不能为空") BigDecimal doseMgPerL,
        @NotBlank(message = "目标工段不能为空") String targetTank,
        LocalDateTime dosedAt,
        String note,
        @NotBlank(message = "操作值班人员不能为空") String operator,
        @NotBlank(message = "加药动作幂等键不能为空") String requestId) {
}
