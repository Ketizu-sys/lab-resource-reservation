package com.azki.reservation.config;

import com.azki.reservation.entity.User;
import com.azki.reservation.entity.UserRole;
import com.azki.reservation.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAccountInitializerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void absentConfigurationShouldNotCreateAccount() throws Exception {
        initializer("", "", "").run();

        verifyNoInteractions(userRepository, passwordEncoder);
    }

    @Test
    void validConfigurationShouldCreateEncryptedAdministrator() throws Exception {
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.empty());
        when(userRepository.existsByUserName("demo-admin")).thenReturn(false);
        when(passwordEncoder.encode("Admin123456!")).thenReturn("bcrypt-hash");

        initializer("admin@example.com", "demo-admin", "Admin123456!").run();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        User saved = captor.getValue();
        assertEquals("admin@example.com", saved.getEmail());
        assertEquals("demo-admin", saved.getUserName());
        assertEquals("bcrypt-hash", saved.getPassword());
        assertEquals(UserRole.ADMIN, saved.getRole());
    }

    @Test
    void existingAdministratorShouldMakeBootstrapIdempotent() throws Exception {
        User existing = user(UserRole.ADMIN);
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(existing));

        initializer("admin@example.com", "demo-admin", "Admin123456!").run();

        verify(userRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void existingOrdinaryUserMustNotBePromoted() {
        when(userRepository.findByEmail("admin@example.com"))
                .thenReturn(Optional.of(user(UserRole.USER)));

        assertThrows(IllegalStateException.class,
                () -> initializer("admin@example.com", "demo-admin", "Admin123456!").run());
        verify(userRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void occupiedUsernameShouldFailClearly() {
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.empty());
        when(userRepository.existsByUserName("demo-admin")).thenReturn(true);

        assertThrows(IllegalStateException.class,
                () -> initializer("admin@example.com", "demo-admin", "Admin123456!").run());
        verify(userRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void incompleteOrWeakConfigurationShouldFailBeforeDatabaseAccess() {
        assertThrows(IllegalStateException.class,
                () -> initializer("admin@example.com", "", "Admin123456!").run());
        assertThrows(IllegalStateException.class,
                () -> initializer("admin@example.com", "demo-admin", "password").run());

        verifyNoInteractions(userRepository, passwordEncoder);
    }

    private AdminAccountInitializer initializer(String email, String username, String password) {
        return new AdminAccountInitializer(userRepository, passwordEncoder, email, username, password);
    }

    private User user(UserRole role) {
        User user = new User();
        user.setEmail("admin@example.com");
        user.setUserName("existing-admin");
        user.setRole(role);
        return user;
    }
}
