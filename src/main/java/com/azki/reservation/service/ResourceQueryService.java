package com.azki.reservation.service;

import com.azki.reservation.dto.resource.ResourceResponseDto;
import com.azki.reservation.entity.Resource;
import com.azki.reservation.entity.ResourceStatus;
import com.azki.reservation.entity.ResourceType;
import com.azki.reservation.exception.ResourceNotFoundException;
import com.azki.reservation.repository.ResourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 普通用户侧资源只读查询服务。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ResourceQueryService {

    private final ResourceRepository resourceRepository;

    public Page<ResourceResponseDto> findResources(
            ResourceType type,
            ResourceStatus requestedStatus,
            String location,
            Pageable pageable) {
        // 普通用户接口绝不返回禁用或维护中的资源。
        if (requestedStatus != null && requestedStatus != ResourceStatus.ACTIVE) {
            return Page.empty(pageable);
        }
        String normalizedLocation =
                location == null || location.isBlank() ? null : location.trim();
        return resourceRepository
                .findVisibleResources(ResourceStatus.ACTIVE, type, normalizedLocation, pageable)
                .map(this::toDto);
    }

    public ResourceResponseDto findResource(Long id) {
        Resource resource = resourceRepository.findByIdAndStatus(id, ResourceStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Active resource not found for id: " + id));
        return toDto(resource);
    }

    private ResourceResponseDto toDto(Resource resource) {
        return new ResourceResponseDto(
                resource.getId(),
                resource.getName(),
                resource.getType(),
                resource.getLocation(),
                resource.getStatus(),
                resource.getCapacity(),
                resource.getDescription());
    }
}
