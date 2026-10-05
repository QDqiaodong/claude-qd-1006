package com.waterworks.plant.dto;

import jakarta.validation.constraints.NotBlank;

public record ResolveConflictRequest(
        Long replacementEquipmentId,
        String excludedReason,
        @NotBlank(message = "操作值班人员不能为空") String operator) {
}
