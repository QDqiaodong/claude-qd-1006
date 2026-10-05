SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;

-- ---------------------------------------------------------------------
-- 自来水厂 · 工艺链监控（取水 → 沉淀 → 过滤 → 消毒 → 清水池）
-- 库名 waterworks_plant；application.yml 里 ddl-auto: none，表结构全部由本文件在 MySQL 镜像构建期导入。
-- 注意：mysql 容器是 UTC，种子数据里的时间一律写 UTC_TIMESTAMP() + INTERVAL 8 HOUR。

CREATE DATABASE IF NOT EXISTS waterworks_plant
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE waterworks_plant;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS disposal_flow_log;
DROP TABLE IF EXISTS disposal_equipment;
DROP TABLE IF EXISTS raw_water_disposal;
DROP TABLE IF EXISTS dosing;
DROP TABLE IF EXISTS water_filter;
DROP TABLE IF EXISTS sedimentation_tank;
DROP TABLE IF EXISTS raw_water;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------- 原水监测 ----------------
-- 水质值对象 water_sample 由自定义 Hibernate UserType 持久化为单列 VARCHAR：ph|turbidity|chlorine
CREATE TABLE raw_water (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_name VARCHAR(32) NOT NULL,
    sample_at DATETIME,
    water_sample VARCHAR(64),
    status VARCHAR(16) NOT NULL,
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 沉淀池 ----------------
CREATE TABLE sedimentation_tank (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tank_code VARCHAR(16) NOT NULL UNIQUE,
    capacity_m3 DECIMAL(12,2) NOT NULL,
    retention_min INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    note VARCHAR(64),
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 滤池 ----------------
CREATE TABLE water_filter (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    filter_code VARCHAR(16) NOT NULL UNIQUE,
    filter_type VARCHAR(16) NOT NULL,
    flow_m3h DECIMAL(10,2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    note VARCHAR(64),
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 加药 ----------------
-- request_id 是一次加药动作的幂等键：重复点击/重试必须返回同一条记录，不能产生第二份。
CREATE TABLE dosing (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chem_name VARCHAR(32) NOT NULL,
    dose_mg_per_l DECIMAL(8,2) NOT NULL,
    target_tank VARCHAR(32) NOT NULL,
    dosed_at DATETIME,
    note VARCHAR(64),
    created_at DATETIME,
    disposal_id BIGINT NULL,
    request_id VARCHAR(64) NULL,
    UNIQUE KEY uk_dosing_request_id (request_id),
    KEY idx_dosing_disposal (disposal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 原水超标联动处置 ----------------
-- active_source_name 只在“待处置/执行中”保存水源名，解除后置 NULL。
-- MySQL 唯一索引允许多个 NULL，因此从数据库层保证同一水源同时最多一条未解除处置。
CREATE TABLE raw_water_disposal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    raw_water_id BIGINT NOT NULL,
    source_name VARCHAR(32) NOT NULL,
    active_source_name VARCHAR(32) NULL,
    status VARCHAR(16) NOT NULL,
    assignee VARCHAR(32) NOT NULL,
    started_at DATETIME NULL,
    released_at DATETIME NULL,
    conclusion VARCHAR(500) NULL,
    release_operator VARCHAR(32) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME,
    updated_at DATETIME,
    UNIQUE KEY uk_active_source_name (active_source_name),
    KEY idx_disposal_status (status),
    KEY idx_disposal_raw_water (raw_water_id),
    CONSTRAINT fk_disposal_raw_water FOREIGN KEY (raw_water_id) REFERENCES raw_water (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 处置关联设备 ----------------
-- 历史关联不删除：冲突后改选会保留原记录为“已改选”，排除则保留为“已排除”。
CREATE TABLE disposal_equipment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    disposal_id BIGINT NOT NULL,
    equipment_type VARCHAR(16) NOT NULL,
    equipment_id BIGINT NOT NULL,
    equipment_code VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    conflict_reason VARCHAR(128) NULL,
    excluded_reason VARCHAR(128) NULL,
    operator VARCHAR(32) NOT NULL,
    resolved_by VARCHAR(32) NULL,
    resolved_at DATETIME NULL,
    created_at DATETIME,
    updated_at DATETIME,
    UNIQUE KEY uk_disposal_equipment (disposal_id, equipment_type, equipment_id),
    KEY idx_disposal_equipment_equipment (equipment_type, equipment_id, status),
    CONSTRAINT fk_disposal_equipment_disposal FOREIGN KEY (disposal_id)
        REFERENCES raw_water_disposal (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 处置流转记录 ----------------
CREATE TABLE disposal_flow_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    disposal_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    from_status VARCHAR(16) NULL,
    to_status VARCHAR(16) NULL,
    operator VARCHAR(32) NOT NULL,
    detail VARCHAR(256) NULL,
    created_at DATETIME,
    KEY idx_flow_disposal (disposal_id, id),
    CONSTRAINT fk_flow_disposal FOREIGN KEY (disposal_id)
        REFERENCES raw_water_disposal (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================================
-- 种子数据
-- =====================================================================

INSERT INTO raw_water (id, source_name, sample_at, water_sample, status, created_at) VALUES
(1, '长江取水泵房', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY, '7.30|1.20|0.60', '合格', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY),
(2, '长江取水泵房', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY, '7.10|2.40|0.45', '预警', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY),
(3, '一号取水口',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 12 DAY, '7.55|0.90|1.20', '合格', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 12 DAY),
(4, '二号取水口',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 DAY,  '8.10|1.80|3.20', '预警', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 DAY),
(5, '二号取水口',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR, '7.90|3.50|2.10', '超标', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR);

INSERT INTO sedimentation_tank (id, tank_code, capacity_m3, retention_min, status, note, created_at) VALUES
(1, 'SED-01', 5000.00, 90, '运行', '一期平流沉淀池', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
(2, 'SED-02', 5000.00, 95, '运行', '二期平流沉淀池', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
(3, 'SED-03', 3200.00, 80, '清洗', '斜管沉淀池，待恢复', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY);

INSERT INTO water_filter (id, filter_code, filter_type, flow_m3h, status, note, created_at) VALUES
(1, 'F-01', '砂滤', 280.00, '运行', 'V 型滤池一组', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
(2, 'F-02', '砂滤', 280.00, '运行', 'V 型滤池二组', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
(3, 'F-03', '炭滤', 160.00, '反洗', '活性炭滤池，反洗中', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY);

INSERT INTO dosing (id, chem_name, dose_mg_per_l, target_tank, dosed_at, note, created_at, disposal_id, request_id) VALUES
(1, '聚合氯化铝', 25.00, '沉淀池-1', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY, '混凝剂前加', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY, NULL, NULL),
(2, '次氯酸钠', 1.20,  '清水池',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY, '出厂水消毒',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY, NULL, NULL),
(3, '聚合氯化铝', 28.00, '沉淀池-2', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY, '原水浊度偏高，加大投加', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY, NULL, NULL);
