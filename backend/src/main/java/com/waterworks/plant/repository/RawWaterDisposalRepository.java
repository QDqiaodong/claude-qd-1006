package com.waterworks.plant.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.waterworks.plant.entity.RawWaterDisposal;

import jakarta.persistence.LockModeType;

public interface RawWaterDisposalRepository extends JpaRepository<RawWaterDisposal, Long> {

    Optional<RawWaterDisposal> findByActiveSourceName(String activeSourceName);

    boolean existsByActiveSourceName(String activeSourceName);

    List<RawWaterDisposal> findByStatusOrderByCreatedAtDesc(String status);

    List<RawWaterDisposal> findAllByOrderByCreatedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from RawWaterDisposal d where d.id = :id")
    Optional<RawWaterDisposal> findLockedById(@Param("id") Long id);
}
