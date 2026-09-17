package com.azki.reservation.service;

import com.azki.reservation.entity.*;
import com.azki.reservation.exception.BusinessException;
import com.azki.reservation.repository.*;
import com.azki.reservation.support.ContainerIntegrationTestSupport;
import org.junit.jupiter.api.*;
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
class AdminReservationIntegrationTest extends ContainerIntegrationTestSupport {
    @Autowired AdminReservationService service;
    @Autowired ReservationRepository reservations;
    @Autowired TimeSlotRepository slots;
    @Autowired ResourceRepository resources;
    @Autowired UserRepository users;

    @BeforeEach void clean() { reservations.deleteAll(); slots.deleteAll(); resources.deleteAll(); }

    @Test
    void shouldFilterPageAndCancelAnotherUsersReservation() {
        User first = user("first"); User second = user("second");
        Resource resource = resource();
        Reservation target = reservation(first, resource, ReservationStatus.ACTIVE, 2);
        reservation(second, resource, ReservationStatus.CANCELLED, 4);

        var page = service.findAll(first.getId(), resource.getId(), ReservationStatus.ACTIVE,
                LocalDateTime.now(), LocalDateTime.now().plusDays(1), PageRequest.of(0, 1));
        assertEquals(1, page.getTotalElements());
        assertEquals(first.getEmail(), page.getContent().getFirst().userEmail());

        service.cancel(target.getId());
        Reservation cancelled = reservations.findById(target.getId()).orElseThrow();
        assertEquals(ReservationStatus.CANCELLED, cancelled.getStatus());
        assertEquals("Cancelled by administrator", cancelled.getCancelReason());
        assertFalse(slots.findById(target.getAvailableSlot().getId()).orElseThrow().isReserved());
        assertThrows(BusinessException.class, () -> service.cancel(target.getId()));
    }

    private User user(String name) {
        User u = new User(); u.setUserName(name); u.setEmail(name + UUID.randomUUID() + "@test.local");
        u.setPassword("encoded"); return users.saveAndFlush(u);
    }
    private Resource resource() {
        Resource r = new Resource(); r.setName("admin-res-" + UUID.randomUUID()); r.setType(ResourceType.LAB);
        r.setLocation("test"); r.setCapacity(1); r.setStatus(ResourceStatus.ACTIVE); return resources.saveAndFlush(r);
    }
    private Reservation reservation(User user, Resource resource, ReservationStatus status, int hours) {
        AvailableSlot s = new AvailableSlot(); s.setResource(resource); s.setStartTime(LocalDateTime.now().plusHours(hours));
        s.setEndTime(s.getStartTime().plusHours(1)); s.setReserved(status == ReservationStatus.ACTIVE); s = slots.saveAndFlush(s);
        Reservation r = new Reservation(); r.setUser(user); r.setAvailableSlot(s); r.setReservedAt(java.time.Instant.now());
        r.setStatus(status); if (status == ReservationStatus.CANCELLED) r.setCancelledAt(java.time.Instant.now());
        return reservations.saveAndFlush(r);
    }
}
