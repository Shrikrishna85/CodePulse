package com.codepulse.auth;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.codepulse.audit.AuditLogService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCK_MINUTES = 15;

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    public AuthService(
            JdbcTemplate jdbcTemplate,
            PasswordEncoder passwordEncoder,
            AuditLogService auditLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public RegisteredUser register(String rawUsername, String rawEmail, String rawPassword) {
        String username = normalizeUsername(rawUsername);
        String email = normalizeEmail(rawEmail);
        validateRegistration(username, email, rawPassword);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        PreparedStatementCreator statementCreator = connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO app_user (username, email, password_hash, role)
                    VALUES (?, ?, ?, 'STUDENT')
                    """,
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, username);
            statement.setString(2, email);
            statement.setString(3, passwordEncoder.encode(rawPassword));
            return statement;
        };

        try {
            jdbcTemplate.update(statementCreator, keyHolder);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username or email is already registered");
        }

        Number generatedId = Objects.requireNonNull(keyHolder.getKey(), "Database did not return a user id");
        long userId = generatedId.longValue();
        auditLogService.record(userId, "USER_REGISTERED", "USER", userId);
        return new RegisteredUser(userId, username, "STUDENT");
    }

    @Transactional
    public void recordFailedLogin(String username) {
        List<LoginState> states = jdbcTemplate.query(
                """
                SELECT id, failed_login_attempts,
                       (locked_until IS NOT NULL AND locked_until <= CURRENT_TIMESTAMP) AS lock_expired
                FROM app_user
                WHERE username = ?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new LoginState(
                        resultSet.getLong("id"),
                        resultSet.getInt("failed_login_attempts"),
                        resultSet.getBoolean("lock_expired")),
                username);

        if (states.isEmpty()) {
            return;
        }

        LoginState state = states.get(0);
        int nextAttemptCount = state.lockExpired() ? 1 : state.failedAttempts() + 1;
        if (nextAttemptCount >= MAX_FAILED_ATTEMPTS) {
            jdbcTemplate.update(
                    """
                    UPDATE app_user
                    SET failed_login_attempts = ?,
                        locked_until = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL ? MINUTE)
                    WHERE id = ?
                    """,
                    nextAttemptCount,
                    LOCK_MINUTES,
                    state.id());
        } else {
            jdbcTemplate.update(
                    """
                    UPDATE app_user
                    SET failed_login_attempts = ?, locked_until = NULL
                    WHERE id = ?
                    """,
                    nextAttemptCount,
                    state.id());
        }
    }

    @Transactional
    public void recordSuccessfulLogin(String username) {
        jdbcTemplate.update(
                """
                UPDATE app_user
                SET failed_login_attempts = 0, locked_until = NULL
                WHERE username = ?
                """,
                username);
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static void validateRegistration(String username, String email, String password) {
        if (!username.matches("[a-z0-9_]{3,40}")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Username must be 3-40 characters using letters, numbers, or underscores");
        }
        if (email.length() > 254 || !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid email address is required");
        }
        if (password == null
                || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Password must be at least 12 characters and no more than 72 UTF-8 bytes");
        }
    }

    private record LoginState(long id, int failedAttempts, boolean lockExpired) {}

    public record RegisteredUser(long id, String username, String role) {}
}
