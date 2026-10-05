package com.waterworks.plant.dto;

/** 并发发起失败时返回的接管结果，前端可直接展示并跳转。 */
public record DisposalTakeoverView(
        Long disposalId,
        String sourceName,
        String status,
        String assignee,
        String message) {
}
