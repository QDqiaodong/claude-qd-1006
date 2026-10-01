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
CREATE TABLE dosing (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chem_name VARCHAR(32) NOT NULL,
    dose_mg_per_l DECIMAL(8,2) NOT NULL,
    target_tank VARCHAR(32) NOT NULL,
    dosed_at DATETIME,
    note VARCHAR(64),
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================================
-- 种子数据（共 13 行）
-- =====================================================================

INSERT INTO raw_water (id, source_name, sample_at, water_sample, status, created_at) VALUES
(1, '长江取水泵房', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY, '7.30|1.20|0.60', '合格', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY),
(2, '长江取水泵房', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY, '7.10|2.40|0.45', '预警', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY),
(3, '一号取水口',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 12 DAY, '7.55|0.90|1.20', '合格', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 12 DAY),
(4, '二号取水口',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 DAY,  '8.10|1.80|3.20', '预警', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 DAY);

INSERT INTO sedimentation_tank (id, tank_code, capacity_m3, retention_min, status, note, created_at) VALUES
(1, 'SED-01', 5000.00, 90, '运行', '一期平流沉淀池', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
(2, 'SED-02', 5000.00, 95, '运行', '二期平流沉淀池', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
(3, 'SED-03', 3200.00, 80, '清洗', '斜管沉淀池，待恢复', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY);

INSERT INTO water_filter (id, filter_code, filter_type, flow_m3h, status, note, created_at) VALUES
(1, 'F-01', '砂滤', 280.00, '运行', 'V 型滤池一组', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
(2, 'F-02', '砂滤', 280.00, '运行', 'V 型滤池二组', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
(3, 'F-03', '炭滤', 160.00, '反洗', '活性炭滤池，反洗中', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY);

INSERT INTO dosing (id, chem_name, dose_mg_per_l, target_tank, dosed_at, note, created_at) VALUES
(1, '聚合氯化铝', 25.00, '沉淀池-1', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY, '混凝剂前加', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY),
(2, '次氯酸钠', 1.20,  '清水池',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY, '出厂水消毒',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY),
(3, '聚合氯化铝', 28.00, '沉淀池-2', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY, '原水浊度偏高，加大投加', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY);
