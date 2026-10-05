package com.waterworks.plant.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.waterworks.plant.entity.DisposalEquipment;

public interface DisposalEquipmentRepository extends JpaRepository<DisposalEquipment, Long> {

    List<DisposalEquipment> findByDisposalIdOrderByEquipmentTypeAscIdAsc(Long disposalId);

    Optional<DisposalEquipment> findByDisposalIdAndEquipmentTypeAndEquipmentId(
            Long disposalId, String equipmentType, Long equipmentId);

    List<DisposalEquipment> findByEquipmentTypeAndEquipmentIdAndStatusInOrderById(
            String equipmentType, Long equipmentId, Collection<String> statuses);

    List<DisposalEquipment> findByDisposalIdAndStatusIn(Long disposalId, Collection<String> statuses);
}
