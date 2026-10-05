package com.waterworks.plant.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.waterworks.plant.entity.Filter;

import jakarta.persistence.LockModeType;

public interface FilterRepository extends JpaRepository<Filter, Long> {

    Optional<Filter> findByFilterCode(String filterCode);

    List<Filter> findByType(String type);

    List<Filter> findByStatus(String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Filter f where f.id = :id")
    Optional<Filter> findLockedById(@Param("id") Long id);
}
