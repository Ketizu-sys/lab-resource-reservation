package com.azki.reservation.service;

import com.azki.reservation.entity.AvailableSlot;
import com.azki.reservation.entity.Reservation;
import com.azki.reservation.entity.ReservationStatus;
import com.azki.reservation.entity.Resource;
import com.azki.reservation.entity.ResourceStatus;
import com.azki.reservation.entity.ResourceType;
import com.azki.reservation.entity.User;
import com.azki.reservation.repository.ReservationRepository;
import com.azki.reservation.repository.ResourceRepository;
import com.azki.reservation.repository.TimeSlotRepository;
import com.azki.reservation.repository.UserRepository;
import com.azki.reservation.support.ContainerIntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 验证本地业务时间与绝对生命周期时间在 MySQL、实体和 API 之间没有八小时偏移。 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TimeSemanticsIntegrationTest extends ContainerIntegrationTestSupport {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired private UserRepository userRepository;
    @Autowired private ResourceRepository resourceRepository;
    @Autowired private TimeSlotRepository timeSlotRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationService reservationService;
    @Autowired private ReservationExpiryService reservationExpiryService;
    @Autowired private ReservationDtoMapper reservationDtoMapper;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void shouldKeepSlotWallClockTimeAndLifecycleInstantConsistent() throws Exception {
        String suffix = UUID.randomUUID().toString();
        User user = createUser("time-1-" + suffix + "@example.com");
        Resource resource = createResource("time-resource-" + suffix);
        LocalDateTime startTime = LocalDateTime.now(BUSINESS_ZONE).plusDays(1).withNano(0);
        AvailableSlot slot = createSlot(resource, startTime, startTime.plusHours(1), false);

        Reservation reservation = reservationService.reserveSlot(user.getId(), slot.getId());
        Reservation reloaded = reservationRepository.findDetailedById(reservation.getId()).orElseThrow();
        assertNotNull(reloaded.getReservedAt());
        assertNotNull(reloaded.getCreatedDate());
        assertEquals(startTime, reloaded.getAvailableSlot().getStartTime());
        var response = reservationDtoMapper.toDto(reloaded);
        LocalDateTime localReservedAt = LocalDateTime.ofInstant(reloaded.getReservedAt(), BUSINESS_ZONE);
        assertEquals(localReservedAt, response.reservedAt());

        String expectedSlotDatabaseTime = startTime
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        assertEquals(expectedSlotDatabaseTime, jdbc.queryForObject(
                "SELECT DATE_FORMAT(start_time, '%Y-%m-%d %H:%i:%s') FROM available_slot WHERE id = ?",
                String.class, slot.getId()));

        JsonNode frontendPayload = objectMapper.readTree(objectMapper.writeValueAsBytes(response));
        assertEquals(startTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                frontendPayload.path("startTime").asText());
        assertEquals(localReservedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                frontendPayload.path("reservedAt").asText());

        String expectedDatabaseUtc = LocalDateTime.ofInstant(reloaded.getReservedAt(), java.time.ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        assertEquals(expectedDatabaseUtc, jdbc.queryForObject(
                "SELECT DATE_FORMAT(reserved_at, '%Y-%m-%d %H:%i:%s') FROM reservation WHERE id = ?",
                String.class, reloaded.getId()));

        reservationService.cancelReservation(reloaded.getId(), user.getId());
        Reservation cancelled = reservationRepository.findById(reloaded.getId()).orElseThrow();
        assertEquals(ReservationStatus.CANCELLED, cancelled.getStatus());
        assertNotNull(cancelled.getCancelledAt());
        assertNotNull(cancelled.getLastModifiedDate());

        User expiredUser = createUser("time-2-" + suffix + "@example.com");
        LocalDateTime pastStart = LocalDateTime.now(BUSINESS_ZONE).minusHours(2).withNano(0);
        AvailableSlot pastSlot = createSlot(resource, pastStart, pastStart.plusHours(1), true);
        Reservation expired = new Reservation();
        expired.setUser(expiredUser);
        expired.setAvailableSlot(pastSlot);
        expired.setReservedAt(Instant.now().minusSeconds(7_200));
        expired = reservationRepository.saveAndFlush(expired);

        reservationExpiryService.processExpiredReservations();
        Reservation completed = reservationRepository.findById(expired.getId()).orElseThrow();
        assertEquals(ReservationStatus.COMPLETED, completed.getStatus());
        assertNotNull(completed.getCompletedAt());

        assertEquals("+00:00", jdbc.queryForObject("SELECT @@session.time_zone", String.class));
        assertEquals("datetime", columnType("reservation", "reserved_at"));
        assertEquals("datetime", columnType("reservation", "created_date"));
        assertEquals("datetime", columnType("available_slot", "start_time"));
    }

    private User createUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setUserName(email);
        user.setPassword("test-password");
        return userRepository.saveAndFlush(user);
    }

    private Resource createResource(String name) {
        Resource resource = new Resource();
        resource.setName(name);
        resource.setType(ResourceType.LAB);
        resource.setLocation("Shanghai");
        resource.setStatus(ResourceStatus.ACTIVE);
        resource.setCapacity(1);
        return resourceRepository.saveAndFlush(resource);
    }

    private AvailableSlot createSlot(Resource resource, LocalDateTime start, LocalDateTime end, boolean reserved) {
        AvailableSlot slot = new AvailableSlot();
        slot.setResource(resource);
        slot.setStartTime(start);
        slot.setEndTime(end);
        slot.setReserved(reserved);
        return timeSlotRepository.saveAndFlush(slot);
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject("""
                SELECT data_type
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, String.class, table, column);
    }
}
