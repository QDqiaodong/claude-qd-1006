package com.waterworks.plant.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.waterworks.plant.entity.SedimentationTank;

import jakarta.persistence.LockModeType;

public interface SedimentationTankRepository extends JpaRepository<SedimentationTank, Long> {

    Optional<SedimentationTank> findByTankCode(String tankCode);

    List<SedimentationTank> findByStatus(String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from SedimentationTank t where t.id = :id")
    Optional<SedimentationTank> findLockedById(@Param("id") Long id);
}
