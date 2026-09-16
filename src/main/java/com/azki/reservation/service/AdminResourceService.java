package com.azki.reservation.service;

import com.azki.reservation.dto.admin.AdminResourceRequest;
import com.azki.reservation.dto.resource.ResourceResponseDto;
import com.azki.reservation.entity.Resource;
import com.azki.reservation.entity.ResourceStatus;
import com.azki.reservation.exception.ResourceNotFoundException;
import com.azki.reservation.repository.ResourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminResourceService {
    private final ResourceRepository repository;

    @Transactional
    public ResourceResponseDto create(AdminResourceRequest request) {
        return toDto(repository.saveAndFlush(apply(new Resource(), request)));
    }

    @Transactional(readOnly = true)
    public Page<ResourceResponseDto> findAll(Pageable pageable) { return repository.findAll(pageable).map(this::toDto); }

    @Transactional
    public ResourceResponseDto update(Long id, AdminResourceRequest request) {
        Resource resource = repository.findById(id).orElseThrow(() -> notFound(id));
        return toDto(repository.saveAndFlush(apply(resource, request)));
    }

    /** 保留历史关联，删除操作仅把资源标记为 DISABLED。 */
    @Transactional
    public void disable(Long id) {
        Resource resource = repository.findById(id).orElseThrow(() -> notFound(id));
        resource.setStatus(ResourceStatus.DISABLED);
        repository.saveAndFlush(resource);
    }

    private Resource apply(Resource r, AdminResourceRequest q) {
        r.setName(q.name().trim()); r.setType(q.type()); r.setLocation(q.location().trim());
        r.setStatus(q.status()); r.setCapacity(q.capacity()); r.setDescription(q.description()); return r;
    }
    private ResourceResponseDto toDto(Resource r) {
        return new ResourceResponseDto(r.getId(), r.getName(), r.getType(), r.getLocation(),
                r.getStatus(), r.getCapacity(), r.getDescription());
    }
    private ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("Resource not found for id: " + id);
    }
}
