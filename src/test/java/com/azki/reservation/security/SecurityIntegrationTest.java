package com.azki.reservation.security;

import com.azki.reservation.entity.Reservation;
import com.azki.reservation.entity.User;
import com.azki.reservation.entity.UserRole;
import com.azki.reservation.repository.UserRepository;
import com.azki.reservation.security.util.JwtUtil;
import com.azki.reservation.service.LoadMonitoringService;
import com.azki.reservation.service.ReservationService;
import com.azki.reservation.service.ReservationQueueService;
import com.azki.reservation.support.ContainerIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证 JWT 身份、角色和预约接口不再信任客户端邮箱。 */
@SpringBootTest(properties = "management.server.port=8080")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SecurityIntegrationTest extends ContainerIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private ReservationService reservationService;

    @MockitoBean
    private LoadMonitoringService loadMonitoringService;

    @MockitoBean
    private ReservationQueueService reservationQueueService;

    private User user;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        user = new User();
        user.setEmail("security-user@example.com");
        user.setUserName("security-user");
        user.setPassword(passwordEncoder.encode("password"));
        user.setRole(UserRole.USER);
        user = userRepository.saveAndFlush(user);
    }

    @Test
    void reservationEndpointShouldRequireAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/reservations/reserve"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedReservationShouldUseTokenUserAndIgnoreForgedEmailBody() throws Exception {
        Reservation reservation = new Reservation();
        reservation.setId(42L);
        when(reservationService.reserveNearestSlot(user.getId())).thenReturn(reservation);
        when(loadMonitoringService.shouldQueueRequest()).thenReturn(false);

        mockMvc.perform(post("/api/v1/reservations/reserve")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"attacker@example.com\"}"))
                .andExpect(status().isOk());

        verify(reservationService).reserveNearestSlot(user.getId());
    }

    @Test
    void ordinaryUserShouldBeForbiddenFromAdminApi() throws Exception {
        mockMvc.perform(get("/api/v1/admin/probe")
                        .header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidTokenShouldBeUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/reservations/reserve")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginShouldRemainAvailableWithoutAuthentication() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"security-user@example.com","password":"password"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void healthEndpointShouldRemainPublicForContainerProbe() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void queuedRequestStatusShouldUseAuthenticatedUserId() throws Exception {
        when(reservationQueueService.getRequestStatus("owned-request", user.getId()))
                .thenReturn("PROCESSING");

        mockMvc.perform(get("/api/v1/reservations/status/owned-request")
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk());

        verify(reservationQueueService).getRequestStatus("owned-request", user.getId());
    }

    @Test
    void queuedRequestStatusShouldBeHiddenWhenItIsNotOwned() throws Exception {
        when(reservationQueueService.getRequestStatus("foreign-request", user.getId()))
                .thenReturn(null);

        mockMvc.perform(get("/api/v1/reservations/status/foreign-request")
                        .header("Authorization", bearer(user)))
                .andExpect(status().isNotFound());
    }

    private String bearer(User target) {
        return "Bearer " + jwtUtil.generateToken(target.getEmail());
    }
}
