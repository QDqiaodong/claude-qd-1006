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

/** 原水超标联动处置的完整流转、设备冲突和加药留痕。 */
@Getter
@Setter
@Entity
@Table(name = "disposal_flow_log")
public class DisposalFlowLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "disposal_id", nullable = false)
    private Long disposalId;

    /** 发起、关联设备、确认设备、设备冲突、改选设备、排除设备、开始执行、登记加药、解除等 */
    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "from_status", length = 16)
    private String fromStatus;

    @Column(name = "to_status", length = 16)
    private String toStatus;

    @Column(name = "operator", nullable = false, length = 32)
    private String operator;

    @Column(name = "detail", length = 256)
    private String detail;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
