package com.azki.reservation.controller;

import com.azki.reservation.entity.*;
import com.azki.reservation.repository.*;
import com.azki.reservation.security.util.JwtUtil;
import com.azki.reservation.service.ReservationService;
import com.azki.reservation.support.ContainerIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证时段删除只针对“没有任何预约历史”的时段。
 *
 * <p>取消和完成都会保留 reservation 行，并把 available_slot.is_reserved 置回 false，
 * 因此只有预约历史才是外键安全的判断依据。真实 PostgreSQL 会在删除被引用的时段时
 * 抛出外键约束异常，这些用例确保该异常不会再以 HTTP 500 的形式暴露给客户端。</p>
 */
@SpringBootTest(properties = "management.server.port=8080")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminSlotDeletionIntegrationTest extends ContainerIntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository users;
    @Autowired private ResourceRepository resources;
    @Autowired private TimeSlotRepository slots;
    @Autowired private ReservationRepository reservations;
    @Autowired private ReservationService reservationService;

    private String adminBearer;

    @BeforeEach
    void setUp() {
        reservations.deleteAll();
        slots.deleteAll();
        resources.deleteAll();
        User admin = new User();
        admin.setUserName("slot-admin-" + UUID.randomUUID());
        admin.setEmail("slot-admin-" + UUID.randomUUID() + "@test.local");
        admin.setPassword(passwordEncoder.encode("password"));
        admin.setRole(UserRole.ADMIN);
        adminBearer = "Bearer " + jwtUtil.generateToken(users.saveAndFlush(admin).getEmail());
    }

    @Test
    void shouldDeleteSlotWithoutAnyReservationHistory() throws Exception {
        AvailableSlot slot = slot(resource());

        mockMvc.perform(delete("/api/v1/admin/slots/" + slot.getId())
                        .header("Authorization", adminBearer))
                .andExpect(status().isNoContent());

        assertTrue(slots.findById(slot.getId()).isEmpty());
    }

    @Test
    void shouldRejectDeletingSlotWithActiveReservation() throws Exception {
        User user = user();
        AvailableSlot slot = slot(resource());
        Reservation reservation = reservationService.reserveSlot(user.getId(), slot.getId());

        String body = mockMvc.perform(delete("/api/v1/admin/slots/" + slot.getId())
                        .header("Authorization", adminBearer))
                .andExpect(status().is4xxClientError())
                .andReturn().getResponse().getContentAsString();

        assertNoInternalLeakage(body);
        assertTrue(slots.findById(slot.getId()).isPresent());
        assertTrue(reservations.findById(reservation.getId()).isPresent());
    }

    /**
     * 这是当前真实存在的核心缺陷：取消预约后 is_reserved 被置回 false，
     * 但 reservation 历史行仍然通过外键引用该时段。
     */
    @Test
    void shouldRejectDeletingSlotWithCancelledReservationHistory() throws Exception {
        User user = user();
        AvailableSlot slot = slot(resource());
        Reservation reservation = reservationService.reserveSlot(user.getId(), slot.getId());
        reservationService.cancelReservation(reservation.getId(), user.getId());

        // 前置条件：取消后时段标记已被释放，正是旧判断失效的场景。
        assertFalse(slots.findById(slot.getId()).orElseThrow().isReserved());
        assertEquals(ReservationStatus.CANCELLED, reservations.findById(reservation.getId()).orElseThrow().getStatus());

        String body = mockMvc.perform(delete("/api/v1/admin/slots/" + slot.getId())
                        .header("Authorization", adminBearer))
                .andExpect(status().is4xxClientError())
                .andReturn().getResponse().getContentAsString();

        assertNoInternalLeakage(body);
        assertTrue(body.contains("reservation history"), body);
        assertTrue(slots.findById(slot.getId()).isPresent());
        assertTrue(reservations.findById(reservation.getId()).isPresent());
    }

    @Test
    void shouldRejectDeletingSlotWithCompletedReservationHistory() throws Exception {
        User user = user();
        AvailableSlot slot = slot(resource());
        Reservation reservation = reservationService.reserveSlot(user.getId(), slot.getId());

        // 模拟过期任务：预约归档为 COMPLETED 并释放时段标记。
        reservation.setStatus(ReservationStatus.COMPLETED);
        reservation.setCompletedAt(Instant.now());
        reservations.saveAndFlush(reservation);
        AvailableSlot freed = slots.findById(slot.getId()).orElseThrow();
        freed.setReserved(false);
        slots.saveAndFlush(freed);

        String body = mockMvc.perform(delete("/api/v1/admin/slots/" + slot.getId())
                        .header("Authorization", adminBearer))
                .andExpect(status().is4xxClientError())
                .andReturn().getResponse().getContentAsString();

        assertNoInternalLeakage(body);
        assertTrue(slots.findById(slot.getId()).isPresent());
        assertTrue(reservations.findById(reservation.getId()).isPresent());
    }

    @Test
    void shouldExposeBusinessMessageInsteadOfSqlForHistoriedSlot() throws Exception {
        User user = user();
        AvailableSlot slot = slot(resource());
        Reservation reservation = reservationService.reserveSlot(user.getId(), slot.getId());
        reservationService.cancelReservation(reservation.getId(), user.getId());

        String body = mockMvc.perform(delete("/api/v1/admin/slots/" + slot.getId())
                        .header("Authorization", adminBearer))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertNoInternalLeakage(body);
    }

    private void assertNoInternalLeakage(String body) {
        String lower = body.toLowerCase();
        List<String> forbidden = List.of(
                "sql", "constraint", "fk_reservation_slot", "hibernate", "jdbc",
                "delete from available_slot", "org.postgresql", "psqlexception", "stacktrace");
        forbidden.forEach(token -> assertFalse(lower.contains(token),
                "响应体不应包含内部实现信息 '" + token + "'，实际响应：" + body));
    }

    private User user() {
        User u = new User();
        u.setUserName("slot-user-" + UUID.randomUUID());
        u.setEmail("slot-user-" + UUID.randomUUID() + "@test.local");
        u.setPassword("encoded");
        u.setRole(UserRole.USER);
        return users.saveAndFlush(u);
    }

    private Resource resource() {
        Resource r = new Resource();
        r.setName("slot-res-" + UUID.randomUUID());
        r.setType(ResourceType.LAB);
        r.setLocation("test");
        r.setCapacity(1);
        r.setStatus(ResourceStatus.ACTIVE);
        return resources.saveAndFlush(r);
    }

    private AvailableSlot slot(Resource resource) {
        LocalDateTime start = LocalDateTime.now().plusDays(1);
        AvailableSlot s = new AvailableSlot();
        s.setResource(resource);
        s.setStartTime(start);
        s.setEndTime(start.plusHours(1));
        return slots.saveAndFlush(s);
    }
}
