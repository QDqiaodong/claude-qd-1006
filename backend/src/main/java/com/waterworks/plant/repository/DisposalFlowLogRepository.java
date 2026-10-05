package com.waterworks.plant.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.waterworks.plant.entity.DisposalFlowLog;

public interface DisposalFlowLogRepository extends JpaRepository<DisposalFlowLog, Long> {

    List<DisposalFlowLog> findByDisposalIdOrderByIdAsc(Long disposalId);
}
