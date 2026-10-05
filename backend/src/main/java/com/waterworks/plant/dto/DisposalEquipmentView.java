package com.waterworks.plant.dto;

import java.time.LocalDateTime;

public record DisposalEquipmentView(
        Long id,
        String equipmentType,
        Long equipmentId,
        String equipmentCode,
        String status,
        String currentStatus,
        String conflictReason,
        String excludedReason,
        String operator,
        String resolvedBy,
        LocalDateTime resolvedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
