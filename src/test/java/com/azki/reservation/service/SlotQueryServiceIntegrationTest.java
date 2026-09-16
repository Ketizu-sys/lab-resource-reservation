package com.azki.reservation.service;

import com.azki.reservation.entity.AvailableSlot;
import com.azki.reservation.entity.Resource;
import com.azki.reservation.entity.ResourceStatus;
import com.azki.reservation.entity.ResourceType;
import com.azki.reservation.repository.ResourceRepository;
import com.azki.reservation.repository.TimeSlotRepository;
import com.azki.reservation.support.ContainerIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SlotQueryServiceIntegrationTest extends ContainerIntegrationTestSupport {
    @Autowired SlotQueryService service;
    @Autowired ResourceRepository resources;
    @Autowired TimeSlotRepository slots;

    @Test
    void shouldApplyAvailabilityFiltersTimeWindowAndPagination() {
        String marker = UUID.randomUUID().toString();
        Resource gpu = resource(marker + "-gpu", ResourceType.GPU, ResourceStatus.ACTIVE);
        Resource lab = resource(marker + "-lab", ResourceType.LAB, ResourceStatus.ACTIVE);
        Resource disabled = resource(marker + "-disabled", ResourceType.GPU, ResourceStatus.MAINTENANCE);
        LocalDateTime base = LocalDateTime.now().plusDays(2).withNano(0);
        AvailableSlot gpu1 = slot(gpu, base, false);
        slot(gpu, base.plusHours(2), false);
        slot(lab, base.plusHours(4), false);
        slot(gpu, base.plusHours(6), true);
        slot(disabled, base.plusHours(8), false);
        slot(gpu, LocalDateTime.now().minusHours(2), false);

        var byResource = service.findAvailableSlots(gpu.getId(), null, null, null, PageRequest.of(0, 1));
        var byType = service.findAvailableSlots(null, ResourceType.LAB, null, null, PageRequest.of(0, 10));
        var byWindow = service.findAvailableSlots(null, null, base.plusHours(1), base.plusHours(6), PageRequest.of(0, 10));

        assertEquals(2, byResource.getTotalElements());
        assertEquals(1, byResource.getSize());
        assertEquals(1, byType.getTotalElements());
        assertEquals(2, byWindow.getTotalElements());
        assertEquals(gpu1.getId(), service.findAvailableSlot(gpu1.getId()).id());
        assertTrue(service.findAvailableSlots(disabled.getId(), null, null, null, PageRequest.of(0, 10)).isEmpty());
    }

    private Resource resource(String name, ResourceType type, ResourceStatus status) {
        Resource r = new Resource(); r.setName(name); r.setType(type); r.setLocation("test");
        r.setStatus(status); r.setCapacity(1); return resources.saveAndFlush(r);
    }

    private AvailableSlot slot(Resource r, LocalDateTime start, boolean reserved) {
        AvailableSlot s = new AvailableSlot(); s.setResource(r); s.setStartTime(start);
        s.setEndTime(start.plusHours(1)); s.setReserved(reserved); return slots.saveAndFlush(s);
    }
}
