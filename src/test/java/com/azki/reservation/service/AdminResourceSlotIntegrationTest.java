package com.azki.reservation.service;

import com.azki.reservation.dto.admin.*;
import com.azki.reservation.entity.*;
import com.azki.reservation.exception.BusinessException;
import com.azki.reservation.repository.*;
import com.azki.reservation.support.ContainerIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminResourceSlotIntegrationTest extends ContainerIntegrationTestSupport {
    @Autowired AdminResourceService resourceService;
    @Autowired AdminSlotService slotService;
    @Autowired ResourceRepository resources;
    @Autowired TimeSlotRepository slots;
    @Autowired ReservationRepository reservations;

    @BeforeEach void clean() { reservations.deleteAll(); slots.deleteAll(); resources.deleteAll(); }

    @Test
    void shouldCreateUpdateAndSoftDisableResource() {
        var created = resourceService.create(new AdminResourceRequest("R-" + UUID.randomUUID(), ResourceType.LAB,
                "A1", ResourceStatus.ACTIVE, 1, "lab"));
        var updated = resourceService.update(created.id(), new AdminResourceRequest(created.name(), ResourceType.LAB,
                "A2", ResourceStatus.MAINTENANCE, 1, "updated"));
        assertEquals("A2", updated.location());
        resourceService.disable(created.id());
        assertEquals(ResourceStatus.DISABLED, resources.findById(created.id()).orElseThrow().getStatus());
        assertTrue(resources.existsById(created.id()));
    }

    @Test
    void shouldRejectCapacityAboveOneInServiceAndDatabase() {
        assertThrows(BusinessException.class, () -> resourceService.create(
                new AdminResourceRequest("invalid-service-" + UUID.randomUUID(), ResourceType.LAB,
                        "A1", ResourceStatus.ACTIVE, 2, null)));

        Resource invalid = new Resource();
        invalid.setName("invalid-db-" + UUID.randomUUID());
        invalid.setType(ResourceType.LAB);
        invalid.setLocation("A1");
        invalid.setStatus(ResourceStatus.ACTIVE);
        invalid.setCapacity(2);
        assertThrows(DataAccessException.class, () -> resources.saveAndFlush(invalid));
    }

    @Test
    void shouldBatchCreateWithoutOverlapAndProtectReservedSlot() {
        var resource = resourceService.create(new AdminResourceRequest("R-" + UUID.randomUUID(), ResourceType.GPU,
                "G1", ResourceStatus.ACTIVE, 1, null));
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        var made = slotService.createBatch(new BatchSlotRequest(resource.id(), tomorrow, tomorrow,
                LocalTime.of(9, 0), LocalTime.of(12, 0), 60));
        assertEquals(3, made.size());
        assertThrows(BusinessException.class, () -> slotService.create(new AdminSlotRequest(resource.id(),
                tomorrow.atTime(9, 30), tomorrow.atTime(10, 30))));

        AvailableSlot reserved = slots.findById(made.getFirst().id()).orElseThrow();
        reserved.setReserved(true); slots.saveAndFlush(reserved);
        assertThrows(BusinessException.class, () -> slotService.update(reserved.getId(),
                new AdminSlotRequest(resource.id(), tomorrow.atTime(13, 0), tomorrow.atTime(14, 0))));
        assertThrows(BusinessException.class, () -> slotService.delete(reserved.getId()));
    }
}
