package com.codepulse.auth;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class JdbcUserDetailsService implements UserDetailsService {

    private static final RowMapper<UserPrincipal> USER_MAPPER = JdbcUserDetailsService::mapUser;

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserDetailsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return jdbcTemplate.query(
                        """
                        SELECT id, username, password_hash, role, enabled,
                               COALESCE(locked_until > CURRENT_TIMESTAMP, FALSE) AS account_locked
                        FROM app_user
                        WHERE username = ?
                        """,
                        USER_MAPPER,
                        username)
                .stream()
                .findFirst()
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    private static UserPrincipal mapUser(ResultSet resultSet, int rowNumber) throws SQLException {
        return new UserPrincipal(
                resultSet.getLong("id"),
                resultSet.getString("username"),
                resultSet.getString("password_hash"),
                resultSet.getString("role"),
                resultSet.getBoolean("enabled"),
                resultSet.getBoolean("account_locked"));
    }
}
