package com.waterworks.plant.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * 处置单关联的沉淀池 / 滤池。
 *
 * <p>关联状态：待确认、已确认、冲突待处理、已排除、已改选。设备在处置期间转清洗 / 反洗时，
 * 原关联不会删除，也不会被新状态静默覆盖，而是保留快照并标成“冲突待处理”。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "disposal_equipment")
public class DisposalEquipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "disposal_id", nullable = false)
    private Long disposalId;

    /** 沉淀池 / 滤池 */
    @Column(name = "equipment_type", nullable = false, length = 16)
    private String equipmentType;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(name = "equipment_code", nullable = false, length = 16)
    private String equipmentCode;

    /** 待确认 / 已确认 / 冲突待处理 / 已排除 / 已改选 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "conflict_reason", length = 128)
    private String conflictReason;

    @Column(name = "excluded_reason", length = 128)
    private String excludedReason;

    @Column(name = "operator", nullable = false, length = 32)
    private String operator;

    @Column(name = "resolved_by", length = 32)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
