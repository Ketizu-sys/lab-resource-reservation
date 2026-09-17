package com.azki.reservation.repository;

import com.azki.reservation.entity.Resource;
import com.azki.reservation.entity.ResourceStatus;
import com.azki.reservation.entity.ResourceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Resource 聚合的数据访问入口。 */
public interface ResourceRepository extends JpaRepository<Resource, Long> {

    Optional<Resource> findByIdAndStatus(Long id, ResourceStatus status);

    Page<Resource> findByStatus(ResourceStatus status, Pageable pageable);

    Page<Resource> findByStatusAndType(
            ResourceStatus status, ResourceType type, Pageable pageable);

    Page<Resource> findByStatusAndLocationContainingIgnoreCase(
            ResourceStatus status, String location, Pageable pageable);

    Page<Resource> findByStatusAndTypeAndLocationContainingIgnoreCase(
            ResourceStatus status, ResourceType type, String location, Pageable pageable);
}
