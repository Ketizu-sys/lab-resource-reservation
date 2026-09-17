package com.azki.reservation.service;

import com.azki.reservation.dto.resource.ResourceResponseDto;
import com.azki.reservation.entity.Resource;
import com.azki.reservation.entity.ResourceStatus;
import com.azki.reservation.entity.ResourceType;
import com.azki.reservation.exception.ResourceNotFoundException;
import com.azki.reservation.repository.ResourceRepository;
import com.azki.reservation.support.ContainerIntegrationTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 验证资源分页、筛选、可见性及 DTO 边界。 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ResourceQueryServiceIntegrationTest extends ContainerIntegrationTestSupport {

    @Autowired
    private ResourceQueryService resourceQueryService;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private String marker;

    @BeforeEach
    void setUp() {
        marker = "query-" + UUID.randomUUID();
    }

    @Test
    void shouldPageFilterAndHideNonActiveResources() {
        Resource first = save("gpu-a", ResourceType.GPU, ResourceStatus.ACTIVE);
        save("gpu-b", ResourceType.GPU, ResourceStatus.ACTIVE);
        save("lab-a", ResourceType.LAB, ResourceStatus.ACTIVE);
        save("gpu-disabled", ResourceType.GPU, ResourceStatus.DISABLED);

        Page<ResourceResponseDto> firstPage = resourceQueryService.findResources(
                null, null, marker, PageRequest.of(0, 2));
        Page<ResourceResponseDto> gpuPage = resourceQueryService.findResources(
                ResourceType.GPU, ResourceStatus.ACTIVE, marker, PageRequest.of(0, 10));
        Page<ResourceResponseDto> hidden = resourceQueryService.findResources(
                null, ResourceStatus.DISABLED, marker, PageRequest.of(0, 10));

        assertEquals(3, firstPage.getTotalElements());
        assertEquals(2, firstPage.getContent().size());
        assertEquals(2, gpuPage.getTotalElements());
        assertTrue(gpuPage.stream().allMatch(dto -> dto.type() == ResourceType.GPU));
        assertTrue(hidden.isEmpty());
        assertEquals(first.getName(), resourceQueryService.findResource(first.getId()).name());
    }

    @Test
    void optionalFiltersShouldWorkForEveryNullAndBlankCombinationOnPostgres() {
        Resource gpu = save("filter-gpu", ResourceType.GPU, ResourceStatus.ACTIVE);
        Resource lab = save("filter-lab", ResourceType.LAB, ResourceStatus.ACTIVE);
        String locationFragment = marker.toUpperCase();

        Page<ResourceResponseDto> withoutFilters = assertDoesNotThrow(() ->
                resourceQueryService.findResources(null, null, null, PageRequest.of(0, 100)));
        Page<ResourceResponseDto> typeOnly = assertDoesNotThrow(() ->
                resourceQueryService.findResources(ResourceType.GPU, null, null, PageRequest.of(0, 100)));
        Page<ResourceResponseDto> locationOnly = assertDoesNotThrow(() ->
                resourceQueryService.findResources(null, null, locationFragment, PageRequest.of(0, 100)));
        Page<ResourceResponseDto> typeAndLocation = assertDoesNotThrow(() ->
                resourceQueryService.findResources(ResourceType.LAB, null, locationFragment, PageRequest.of(0, 100)));
        Page<ResourceResponseDto> emptyLocation = assertDoesNotThrow(() ->
                resourceQueryService.findResources(null, null, "", PageRequest.of(0, 100)));
        Page<ResourceResponseDto> blankLocation = assertDoesNotThrow(() ->
                resourceQueryService.findResources(null, null, "   ", PageRequest.of(0, 100)));
        Page<ResourceResponseDto> missingLocation = assertDoesNotThrow(() ->
                resourceQueryService.findResources(null, null, "missing-" + UUID.randomUUID(), PageRequest.of(0, 100)));

        assertTrue(withoutFilters.stream().anyMatch(dto -> dto.id().equals(gpu.getId())));
        assertTrue(withoutFilters.stream().anyMatch(dto -> dto.id().equals(lab.getId())));
        assertTrue(typeOnly.stream().anyMatch(dto -> dto.id().equals(gpu.getId())));
        assertTrue(typeOnly.stream().allMatch(dto -> dto.type() == ResourceType.GPU));
        assertEquals(2, locationOnly.getTotalElements());
        assertEquals(1, typeAndLocation.getTotalElements());
        assertEquals(lab.getId(), typeAndLocation.getContent().getFirst().id());
        assertTrue(emptyLocation.stream().anyMatch(dto -> dto.id().equals(gpu.getId())));
        assertTrue(blankLocation.stream().anyMatch(dto -> dto.id().equals(lab.getId())));
        assertTrue(missingLocation.isEmpty());
    }

    @Test
    void disabledOrMissingResourceShouldReturnNotFound() {
        Resource disabled = save("hidden", ResourceType.EQUIPMENT, ResourceStatus.MAINTENANCE);

        assertThrows(ResourceNotFoundException.class,
                () -> resourceQueryService.findResource(disabled.getId()));
        assertThrows(ResourceNotFoundException.class,
                () -> resourceQueryService.findResource(Long.MAX_VALUE));
    }

    @Test
    void responseDtoShouldNotExposeAuditFields() throws Exception {
        Resource active = save("dto", ResourceType.WORKSTATION, ResourceStatus.ACTIVE);

        String json = objectMapper.writeValueAsString(resourceQueryService.findResource(active.getId()));

        assertFalse(json.contains("createdDate"));
        assertFalse(json.contains("lastModifiedDate"));
        assertFalse(json.contains("version"));
    }

    private Resource save(String suffix, ResourceType type, ResourceStatus status) {
        Resource resource = new Resource();
        resource.setName(marker + "-" + suffix);
        resource.setType(type);
        resource.setLocation("Building " + marker);
        resource.setStatus(status);
        resource.setCapacity(1);
        resource.setDescription("test");
        return resourceRepository.saveAndFlush(resource);
    }
}
