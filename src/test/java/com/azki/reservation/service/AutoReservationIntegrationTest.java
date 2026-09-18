package com.azki.reservation.service;

import com.azki.reservation.entity.*;
import com.azki.reservation.repository.*;
import com.azki.reservation.support.ContainerIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class AutoReservationIntegrationTest extends ContainerIntegrationTestSupport {
    @Autowired ReservationService service;
    @Autowired UserRepository users;
    @Autowired ResourceRepository resources;
    @Autowired TimeSlotRepository slots;
    @Autowired ReservationRepository reservations;

    @BeforeEach void clean() { reservations.deleteAll(); slots.deleteAll(); resources.deleteAll(); }

    @Test
    void shouldSkipInactiveResourceAndChooseNearestActiveFutureSlot() {
        User user = user();
        AvailableSlot maintenance = slot(resource(ResourceStatus.MAINTENANCE), LocalDateTime.now().plusHours(1));
        AvailableSlot active = slot(resource(ResourceStatus.ACTIVE), LocalDateTime.now().plusHours(2));

        Reservation result = service.reserveNearestSlot(user.getId());

        assertEquals(active.getId(), result.getAvailableSlot().getId());
        assertFalse(slots.findById(maintenance.getId()).orElseThrow().isReserved());
    }

    private User user() {
        User u = new User(); u.setUserName("auto"); u.setEmail(UUID.randomUUID() + "@test.local");
        u.setPassword("encoded"); return users.saveAndFlush(u);
    }
    private Resource resource(ResourceStatus status) {
        Resource r = new Resource(); r.setName("auto-" + UUID.randomUUID()); r.setType(ResourceType.GPU);
        r.setLocation("test"); r.setCapacity(1); r.setStatus(status); return resources.saveAndFlush(r);
    }
    private AvailableSlot slot(Resource resource, LocalDateTime start) {
        AvailableSlot s = new AvailableSlot(); s.setResource(resource); s.setStartTime(start);
        s.setEndTime(start.plusHours(1)); return slots.saveAndFlush(s);
    }
}
