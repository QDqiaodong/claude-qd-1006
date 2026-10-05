package com.waterworks.plant.dto;

import jakarta.validation.constraints.NotBlank;

public record ReleaseDisposalRequest(
        @NotBlank(message = "处理结论不能为空") String conclusion,
        @NotBlank(message = "操作值班人员不能为空") String operator) {
}
