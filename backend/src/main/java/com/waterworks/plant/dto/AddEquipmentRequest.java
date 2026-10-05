package com.waterworks.plant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AddEquipmentRequest(
        @NotBlank(message = "设备类型不能为空") String equipmentType,
        @NotNull(message = "设备不能为空") Long equipmentId,
        @NotBlank(message = "操作值班人员不能为空") String operator) {
}
