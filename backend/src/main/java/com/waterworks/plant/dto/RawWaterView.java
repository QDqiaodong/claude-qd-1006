package com.waterworks.plant.dto;

import java.time.LocalDateTime;

import com.waterworks.plant.type.WaterSample;

public record RawWaterView(
        Long id,
        String sourceName,
        LocalDateTime sampleAt,
        WaterSample waterSample,
        String status) {
}
