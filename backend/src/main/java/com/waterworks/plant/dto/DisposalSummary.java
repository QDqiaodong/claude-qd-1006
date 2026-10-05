package com.waterworks.plant.dto;

import java.time.LocalDateTime;

public record DisposalSummary(
        Long id,
        Long rawWaterId,
        String sourceName,
        String status,
        String assignee,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime releasedAt,
        String conclusion,
        String releaseOperator,
        Integer tankCount,
        Integer filterCount,
        Integer effectiveTankCount,
        Integer effectiveFilterCount,
        Integer conflictCount,
        Integer dosingCount) {
}
