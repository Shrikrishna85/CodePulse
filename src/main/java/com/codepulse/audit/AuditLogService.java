package com.codepulse.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditLogService {

    private final JdbcTemplate jdbcTemplate;

    public AuditLogService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(Long actorUserId, String eventType, String entityType, Long entityId) {
        jdbcTemplate.update(
                """
                INSERT INTO audit_log (actor_user_id, event_type, entity_type, entity_id)
                VALUES (?, ?, ?, ?)
                """,
                actorUserId,
                eventType,
                entityType,
                entityId);
    }
}
