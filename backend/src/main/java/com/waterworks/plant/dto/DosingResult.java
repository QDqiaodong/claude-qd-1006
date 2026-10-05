package com.waterworks.plant.dto;

import com.waterworks.plant.entity.Dosing;

/** 加药登记结果：idempotent=false 表示命中重复点击，返回的是原有记录。 */
public record DosingResult(Dosing dosing, boolean idempotent, String message) {

    public static DosingResult created(Dosing dosing) {
        return new DosingResult(dosing, true, "加药已登记");
    }

    public static DosingResult existing(Dosing dosing) {
        return new DosingResult(dosing, false, "同一加药动作已提交，已返回原记录，未重复登记");
    }
}
