package com.azki.reservation.service;

import com.azki.reservation.entity.*;
import com.azki.reservation.exception.BusinessException;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ManualReservationIntegrationTest extends ContainerIntegrationTestSupport {
    @Autowired ReservationService service;
    @Autowired UserRepository users;
    @Autowired ResourceRepository resources;
    @Autowired TimeSlotRepository slots;
    @Autowired ReservationRepository reservations;

    @BeforeEach
    void clean() {
        reservations.deleteAll(); slots.deleteAll(); resources.deleteAll();
    }

    @Test
    void shouldReserveSpecifiedSlotAndRejectInvalidCandidatesWithoutPartialWrites() {
        User user = user("manual");
        Resource active = resource(ResourceStatus.ACTIVE);
        AvailableSlot valid = slot(active, LocalDateTime.now().plusHours(3), false);

        Reservation created = service.reserveSlot(user.getId(), valid.getId());
        assertEquals(ReservationStatus.ACTIVE, created.getStatus());
        assertTrue(slots.findById(valid.getId()).orElseThrow().isReserved());

        Resource maintenance = resource(ResourceStatus.MAINTENANCE);
        AvailableSlot blocked = slot(maintenance, LocalDateTime.now().plusHours(5), false);
        long count = reservations.count();
        assertThrows(BusinessException.class, () -> service.reserveSlot(user.getId(), blocked.getId()));
        assertEquals(count, reservations.count());
        assertFalse(slots.findById(blocked.getId()).orElseThrow().isReserved());
    }

    @Test
    void twoUsersCompetingForSameSlotShouldCreateOnlyOneReservation() throws Exception {
        User first = user("first"); User second = user("second");
        AvailableSlot target = slot(resource(ResourceStatus.ACTIVE), LocalDateTime.now().plusHours(2), false);
        CountDownLatch gate = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> a = executor.submit(() -> reserve(gate, first.getId(), target.getId()));
            Future<Boolean> b = executor.submit(() -> reserve(gate, second.getId(), target.getId()));
            gate.countDown();
            assertEquals(1, List.of(a.get(), b.get()).stream().filter(Boolean::booleanValue).count());
        }
        assertEquals(1, reservations.count());
    }

    private boolean reserve(CountDownLatch gate, Long userId, Long slotId) throws InterruptedException {
        gate.await();
        try { service.reserveSlot(userId, slotId); return true; }
        catch (BusinessException ex) { return false; }
    }

    private User user(String marker) {
        User u = new User(); u.setUserName(marker); u.setEmail(marker + UUID.randomUUID() + "@test.local");
        u.setPassword("encoded"); return users.saveAndFlush(u);
    }
    private Resource resource(ResourceStatus status) {
        Resource r = new Resource(); r.setName("resource-" + UUID.randomUUID()); r.setType(ResourceType.LAB);
        r.setLocation("test"); r.setCapacity(1); r.setStatus(status); return resources.saveAndFlush(r);
    }
    private AvailableSlot slot(Resource resource, LocalDateTime start, boolean reserved) {
        AvailableSlot s = new AvailableSlot(); s.setResource(resource); s.setStartTime(start);
        s.setEndTime(start.plusHours(1)); s.setReserved(reserved); return slots.saveAndFlush(s);
    }
}
