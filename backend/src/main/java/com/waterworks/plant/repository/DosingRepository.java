package com.waterworks.plant.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.waterworks.plant.entity.Dosing;

public interface DosingRepository extends JpaRepository<Dosing, Long> {

    List<Dosing> findByChemName(String chemName);

    List<Dosing> findByTargetTank(String targetTank);

    Optional<Dosing> findByRequestId(String requestId);

    List<Dosing> findByDisposalIdOrderByDosedAtDescIdDesc(Long disposalId);
}
