package com.codepulse.problem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.codepulse.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class ProblemServiceTest {

    private JdbcTemplate jdbcTemplate;
    private AuditLogService auditLogService;
    private ProblemService problemService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        auditLogService = mock(AuditLogService.class);
        problemService = new ProblemService(jdbcTemplate, auditLogService);
    }

    @Test
    void createProblemRejectsUnsupportedDifficultyBeforeDatabaseAccess() {
        ProblemService.CreateProblem request = new ProblemService.CreateProblem(
                "two-sum", "Two Sum", "Find two values.", "IMPOSSIBLE", 1000, 128, null);

        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, () -> problemService.createProblem(request, 1));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(jdbcTemplate, auditLogService);
    }

    @Test
    void createProblemRejectsLimitsOutsideSupportedRange() {
        ProblemService.CreateProblem request = new ProblemService.CreateProblem(
                "two-sum", "Two Sum", "Find two values.", "EASY", 100, 128, null);

        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, () -> problemService.createProblem(request, 1));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(jdbcTemplate, auditLogService);
    }

    @Test
    void createProblemRejectsMoreThanTenDistinctTagsBeforeDatabaseAccess() {
        ProblemService.CreateProblem request = new ProblemService.CreateProblem(
                "two-sum",
                "Two Sum",
                "Find two values.",
                "EASY",
                1000,
                128,
                java.util.List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"));

        ResponseStatusException exception =
                assertThrows(ResponseStatusException.class, () -> problemService.createProblem(request, 1));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(jdbcTemplate, auditLogService);
    }

    @Test
    void addTestCaseRejectsMissingExpectedOutputBeforeDatabaseAccess() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> problemService.addTestCase(1, new ProblemService.NewTestCase("", null, false), 1));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verifyNoInteractions(jdbcTemplate, auditLogService);
    }
}
