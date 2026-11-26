# CodePulse design

## Persistence and transactions

The schema is managed by ordered SQL files under `src/main/resources/db/migration`; this project intentionally does not add a migration framework. Apply every `V*.sql` once, in lexical order, to an empty MySQL 8 database.

Services use `JdbcTemplate` and explicit transactions for multi-row changes. Problem creation inserts the problem, tags, tag links, and audit event in one transaction. Submission creation validates a published problem and inserts the `QUEUED` row and audit event transactionally. Result persistence replaces per-test rows and changes the parent submission from `RUNNING` to a terminal verdict in one transaction.

## Queue and recovery

Each of the four worker threads claims at most one row at a time:

```sql
SELECT ...
FROM submission
WHERE verdict = 'QUEUED'
ORDER BY queued_at, id
LIMIT 1
FOR UPDATE SKIP LOCKED;
```

The same transaction changes the selected row to `RUNNING` before releasing its lock. Other workers skip that row instead of blocking on it. Work execution happens after the claim transaction commits, so a compiler or user process never holds a database lock.

At Linux worker startup, rows left `RUNNING` are returned to `QUEUED`. Results are persisted only after all test cases complete; retrying a recovered job deletes any old per-test results before writing a fresh set. This assumes one application instance. Multiple active instances need leases/heartbeats and ownership tokens so one instance cannot requeue another live instance's jobs.

## Judge lifecycle

The runner creates a unique temporary directory, writes `Main.java` or `main.c`, compiles once, runs each case with piped stdin, stores only verdict/timing and sample feedback, and deletes the directory in `finally`. Output comparison normalizes CRLF/CR to LF and removes trailing spaces/tabs per line; it preserves leading whitespace and extra blank lines.

For each process, the Linux runner clears inherited environment variables, sets a private working directory and temporary home, captures bounded stdout/stderr, applies `timeout` and `ulimit` limits, and uses `setsid` so timeout/output-limit cleanup can kill the process group. Java uses `-Xmx` and `-Xss`; its address-space limit has a 512 MiB floor to allow the JVM to start, so the Java heap flag is the tighter requested limit, not a strict whole-process memory measurement.

Verdicts are returned for compilation errors, runtime errors, wrong answers, time/memory/output limits, internal errors, and accepted submissions. Hidden stdin and expected output are used only inside the worker. They are not included in problem detail, submission detail, or per-test API responses.

## Contests, similarity, analytics

The scoreboard orders by solved count descending, penalty ascending, then username. Each accepted problem adds elapsed whole minutes from contest start and 20 minutes for each earlier non-accepted attempt; later attempts do not add penalty.

Similarity compares token 5-shingle sets for accepted submissions of the same problem. Scores above 0.80 are stored for admin review; the check never bans or changes an account. It is intentionally a heuristic, not proof of misconduct.

Analytics are aggregate JDBC queries rendered by hand-drawn `<canvas>` bars. No chart or frontend framework is used.

## Future improvements

- Lease/heartbeat based recovery for multiple application instances.
- Isolated per-job UID/container or stronger sandbox runtime, cgroups, syscall filtering, and network/filesystem isolation.
- Reliable per-process memory accounting and integration tests/benchmarks on each supported Linux distribution.
- Cursor pagination, durable distributed rate limiting, and contest administration APIs.
