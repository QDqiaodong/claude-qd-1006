package com.waterworks.plant.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.Setter;

/**
 * 原水超标联动处置主单。
 *
 * <p>状态按 待处置 → 执行中 → 已解除 单向推进。{@code activeSourceName} 是数据库唯一约束使用的
 * 派生字段：未解除时等于水源名，解除后置空，确保同一水源同一时刻只存在一条未解除处置。</p>
 */
@Getter
@Setter
@Entity
@Table(name = "raw_water_disposal")
public class RawWaterDisposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "raw_water_id", nullable = false)
    private Long rawWaterId;

    /** 发起时固化水源名称，避免后续监测记录改名影响历史处置 */
    @Column(name = "source_name", nullable = false, length = 32)
    private String sourceName;

    /** 未解除时等于 sourceName；已解除为 null，配合唯一索引实现并发唯一 */
    @Column(name = "active_source_name", length = 32)
    private String activeSourceName;

    /** 待处置 / 执行中 / 已解除 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 当前责任人 / 接管值班人员 */
    @Column(name = "assignee", nullable = false, length = 32)
    private String assignee;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "conclusion", length = 500)
    private String conclusion;

    @Column(name = "release_operator", length = 32)
    private String releaseOperator;

    @Version
    private Integer version;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
