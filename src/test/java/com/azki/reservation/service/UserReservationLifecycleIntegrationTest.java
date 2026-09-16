package com.azki.reservation.service;

import com.azki.reservation.entity.*;
import com.azki.reservation.exception.BusinessException;
import com.azki.reservation.exception.ReservationNotFoundException;
import com.azki.reservation.repository.*;
import com.azki.reservation.support.ContainerIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
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
class UserReservationLifecycleIntegrationTest extends ContainerIntegrationTestSupport {
    @Autowired UserReservationQueryService queries;
    @Autowired ReservationService service;
    @Autowired ReservationRepository reservations;
    @Autowired TimeSlotRepository slots;
    @Autowired ResourceRepository resources;
    @Autowired UserRepository users;

    @BeforeEach void clean() { reservations.deleteAll(); slots.deleteAll(); resources.deleteAll(); }

    @Test
    void shouldOnlyListCurrentUserWithStatusAndPaginationAndProtectDetails() {
        User mine = user("mine"); User other = user("other"); Resource resource = resource();
        Reservation active = reservation(mine, resource, 2, ReservationStatus.ACTIVE);
        reservation(mine, resource, 4, ReservationStatus.CANCELLED);
        Reservation foreign = reservation(other, resource, 6, ReservationStatus.ACTIVE);

        var minePage = queries.findMine(mine.getId(), null, PageRequest.of(0, 1));
        var activePage = queries.findMine(mine.getId(), ReservationStatus.ACTIVE, PageRequest.of(0, 10));
        assertEquals(2, minePage.getTotalElements());
        assertEquals(1, minePage.getContent().size());
        assertEquals(1, activePage.getTotalElements());
        assertEquals(active.getId(), queries.findOwned(active.getId(), mine.getId()).id());
        assertThrows(AccessDeniedException.class, () -> queries.findOwned(foreign.getId(), mine.getId()));
        assertThrows(ReservationNotFoundException.class, () -> queries.findOwned(Long.MAX_VALUE, mine.getId()));
    }

    @Test
    void cancellationShouldReleaseSlotButKeepHistoryAndRejectInvalidState() {
        User mine = user("cancel"); Resource resource = resource();
        Reservation active = reservation(mine, resource, 3, ReservationStatus.ACTIVE);
        Long slotId = active.getAvailableSlot().getId();

        service.cancelReservation(active.getId(), mine.getId());

        assertEquals(ReservationStatus.CANCELLED, reservations.findById(active.getId()).orElseThrow().getStatus());
        assertFalse(slots.findById(slotId).orElseThrow().isReserved());
        assertThrows(BusinessException.class, () -> service.cancelReservation(active.getId(), mine.getId()));
        assertTrue(reservations.existsById(active.getId()));
    }

    private User user(String name) {
        User u = new User(); u.setUserName(name); u.setEmail(name + UUID.randomUUID() + "@test.local");
        u.setPassword("encoded"); return users.saveAndFlush(u);
    }
    private Resource resource() {
        Resource r = new Resource(); r.setName("resource-" + UUID.randomUUID()); r.setType(ResourceType.GPU);
        r.setLocation("test"); r.setCapacity(1); r.setStatus(ResourceStatus.ACTIVE); return resources.saveAndFlush(r);
    }
    private Reservation reservation(User user, Resource resource, int hours, ReservationStatus status) {
        AvailableSlot s = new AvailableSlot(); s.setResource(resource); s.setStartTime(LocalDateTime.now().plusHours(hours));
        s.setEndTime(s.getStartTime().plusHours(1)); s.setReserved(status == ReservationStatus.ACTIVE);
        s = slots.saveAndFlush(s);
        Reservation r = new Reservation(); r.setUser(user); r.setAvailableSlot(s); r.setReservedAt(LocalDateTime.now());
        r.setStatus(status); if (status == ReservationStatus.CANCELLED) r.setCancelledAt(LocalDateTime.now());
        return reservations.saveAndFlush(r);
    }
}
