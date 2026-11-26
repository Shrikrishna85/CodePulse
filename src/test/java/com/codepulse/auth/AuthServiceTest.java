package com.codepulse.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

import com.codepulse.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

class AuthServiceTest {

    private JdbcTemplate jdbcTemplate;
    private PasswordEncoder passwordEncoder;
    private AuditLogService auditLogService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        passwordEncoder = org.mockito.Mockito.mock(PasswordEncoder.class);
        auditLogService = org.mockito.Mockito.mock(AuditLogService.class);
        authService = new AuthService(jdbcTemplate, passwordEncoder, auditLogService);
    }

    @Test
    void registrationRejectsInvalidUsernameBeforeDatabaseAccess() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> authService.register("ab", "student@example.com", "a-strong-password"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(jdbcTemplate, passwordEncoder, auditLogService);
    }

    @Test
    void registrationRejectsPasswordShorterThanTwelveCharacters() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> authService.register("student_1", "student@example.com", "short"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(jdbcTemplate, passwordEncoder, auditLogService);
    }
}
