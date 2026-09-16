package com.azki.reservation.repository;

import com.azki.reservation.entity.Resource;
import org.springframework.data.jpa.repository.JpaRepository;

/** Resource 聚合的数据访问入口；查询接口会在后续阶段单独增加。 */
public interface ResourceRepository extends JpaRepository<Resource, Long> {
}
