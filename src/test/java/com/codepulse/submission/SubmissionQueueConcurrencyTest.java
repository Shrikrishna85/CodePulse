package com.codepulse.submission;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfSystemProperty(named = "codepulse.mysql.integration", matches = "true")
class SubmissionQueueConcurrencyTest {

    private JdbcTemplate jdbcTemplate;
    private SubmissionQueueService queueService;

    @BeforeEach
    void setUp() {
        String url = requiredProperty("codepulse.mysql.test.url");
        String username = requiredProperty("codepulse.mysql.test.username");
        String password = System.getProperty("codepulse.mysql.test.password", "");
        DataSource dataSource = new DriverManagerDataSource(url, username, password);
        jdbcTemplate = new JdbcTemplate(dataSource);
        String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        if (database == null || !database.toLowerCase().endsWith("_test")) {
            throw new IllegalStateException("Queue integration tests require a database whose name ends with _test");
        }

        jdbcTemplate.execute("DROP TABLE IF EXISTS submission");
        jdbcTemplate.execute("DROP TABLE IF EXISTS problem");
        jdbcTemplate.execute("""
                CREATE TABLE problem (
                    id BIGINT PRIMARY KEY,
                    time_limit_ms INT NOT NULL,
                    memory_limit_mb INT NOT NULL
                ) ENGINE=InnoDB
                """);
        jdbcTemplate.execute("""
                CREATE TABLE submission (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    user_id BIGINT NOT NULL,
                    problem_id BIGINT NOT NULL,
                    language VARCHAR(8) NOT NULL,
                    source_code MEDIUMTEXT NOT NULL,
                    verdict VARCHAR(32) NOT NULL,
                    queued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    started_at TIMESTAMP NULL
                ) ENGINE=InnoDB
                """);
        jdbcTemplate.update("INSERT INTO problem VALUES (1, 1000, 128)");
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO submission (user_id, problem_id, language, source_code, verdict)
                VALUES (1, 1, 'JAVA', 'class Main {}', 'QUEUED')
                """,
                new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(java.sql.PreparedStatement statement, int index)
                            throws java.sql.SQLException {}

                    @Override
                    public int getBatchSize() {
                        return 150;
                    }
                });
        queueService = new SubmissionQueueService(
                jdbcTemplate,
                new DataSourceTransactionManager(dataSource));
    }

    @AfterEach
    void cleanUp() {
        if (jdbcTemplate != null) {
            jdbcTemplate.execute("DROP TABLE IF EXISTS submission");
            jdbcTemplate.execute("DROP TABLE IF EXISTS problem");
        }
    }

    @Test
    void fourWorkersClaimAll150RowsExactlyOnce() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(4);
        Set<Long> claimedIds = ConcurrentHashMap.newKeySet();
        AtomicInteger claimCount = new AtomicInteger();
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                tasks.add(workers.submit(() -> {
                    int idlePolls = 0;
                    while (claimedIds.size() < 150 && idlePolls < 2_000) {
                        var claimed = queueService.claimNext();
                        if (claimed.isPresent()) {
                            claimCount.incrementAndGet();
                            claimedIds.add(claimed.get().id());
                            idlePolls = 0;
                        } else {
                            idlePolls++;
                            Thread.sleep(5);
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> task : tasks) {
                task.get();
            }
        } finally {
            workers.shutdownNow();
        }

        assertEquals(150, claimCount.get());
        assertEquals(150, claimedIds.size());
        assertEquals(150, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission WHERE verdict = 'RUNNING'",
                Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission WHERE verdict = 'QUEUED'",
                Integer.class));
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required test property: " + name);
        }
        return value;
    }
}
