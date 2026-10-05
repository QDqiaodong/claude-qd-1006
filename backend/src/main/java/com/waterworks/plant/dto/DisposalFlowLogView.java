package com.waterworks.plant.dto;

import java.time.LocalDateTime;

public record DisposalFlowLogView(
        Long id,
        String action,
        String fromStatus,
        String toStatus,
        String operator,
        String detail,
        LocalDateTime createdAt) {
}
