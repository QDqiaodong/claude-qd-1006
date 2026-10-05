package com.waterworks.plant.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateDisposalRequest(
        @NotNull(message = "原水监测记录不能为空") Long rawWaterId,
        @NotBlank(message = "责任值班人员不能为空") String assignee,
        List<Long> tankIds,
        List<Long> filterIds) {
}
