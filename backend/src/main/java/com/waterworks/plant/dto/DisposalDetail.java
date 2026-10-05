package com.waterworks.plant.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.waterworks.plant.entity.Dosing;

public record DisposalDetail(
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
        Integer dosingCount,
        RawWaterView rawWater,
        List<DisposalEquipmentView> equipments,
        List<Dosing> dosings,
        List<DisposalFlowLogView> flowLogs) {
}
