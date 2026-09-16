package com.azki.reservation.repository;

import com.azki.reservation.entity.Resource;
import com.azki.reservation.entity.ResourceStatus;
import com.azki.reservation.entity.ResourceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Resource 聚合的数据访问入口。 */
public interface ResourceRepository extends JpaRepository<Resource, Long> {

    Optional<Resource> findByIdAndStatus(Long id, ResourceStatus status);

    /** 普通用户资源检索：状态固定为 ACTIVE，可选类型和位置关键字。 */
    @Query("""
            SELECT r FROM Resource r
            WHERE r.status = :status
              AND (:type IS NULL OR r.type = :type)
              AND (:location IS NULL OR LOWER(r.location) LIKE LOWER(CONCAT('%', :location, '%')))
            """)
    Page<Resource> findVisibleResources(
            @Param("status") ResourceStatus status,
            @Param("type") ResourceType type,
            @Param("location") String location,
            Pageable pageable);
}
