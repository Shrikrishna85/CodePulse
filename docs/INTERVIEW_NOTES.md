# CodePulse interview notes

1. **Why JDBC instead of JPA?**  
   The project uses explicit SQL and transactions so I can explain row locking, indexes, and each query without hiding them behind ORM behavior.

2. **How do workers avoid claiming the same submission?**  
   Each worker selects one queued row with `FOR UPDATE SKIP LOCKED`, changes it to `RUNNING` inside the same short transaction, commits, then executes outside the transaction.

3. **Why not keep a transaction open while compiling?**  
   Compilation and user programs can take seconds or time out. Holding a row lock for that whole period would block unrelated workers and keep database connections busy.

4. **What happens after a process crash?**  
   On Linux worker startup, `RUNNING` rows are re-queued. Results are saved at the end of a complete judge run, so a retry replaces any prior per-test rows.

5. **How do you prevent two active app instances from re-queuing each other's work?**  
   The demo assumes one active instance. A multi-instance design needs a worker lease/heartbeat and an owner token before recovery can safely requeue work.

6. **Is `ulimit` a complete sandbox?**  
   No. It limits some resource use but does not stop filesystem or network access, kernel attacks, or all process abuse. The current runner must not be exposed as a public hostile-code service.

7. **How are Java memory limits handled?**  
   Java receives `-Xmx` and `-Xss`, plus an address-space limit with a startup floor. This is not exact RSS measurement; a production judge should use cgroups or stronger isolation.

8. **How do you avoid leaking hidden tests?**  
   Public problem detail selects only `sample = TRUE`; result APIs return verdicts and timings, not hidden stdin/expected output. Feedback snippets are stored only for samples.

9. **How does the output checker behave?**  
   It normalizes CRLF/CR to LF and trims trailing spaces/tabs on each line. It preserves leading spaces and extra blank lines so it does not silently accept materially different output.

10. **Why persist every test verdict?**  
    It gives the UI useful progress/detail and makes the final result inspectable without returning any hidden test data.

11. **How does the scoreboard penalty work?**  
    It sorts by solved count descending and penalty ascending. An accepted solution adds elapsed whole minutes plus 20 minutes for each earlier failed attempt on that problem.

12. **Does similarity detection prove plagiarism?**  
    No. It compares token 5-shingle sets for accepted same-problem submissions and only flags high overlap for human review. Similar solutions can be legitimate.

13. **Why use a token bucket for rate limiting?**  
    It permits a small burst while bounding sustained submission rate. This implementation is process-local; a multi-instance deployment needs shared state or a gateway limiter.

14. **How are passwords stored?**  
    BCrypt hashes are stored, never plaintext. Password length is capped by UTF-8 bytes because BCrypt has a 72-byte input limit.

15. **How is CSRF handled for the JavaScript client?**  
    The client fetches a CSRF token, keeps the cookie, and sends the token in `X-XSRF-TOKEN` for state-changing requests.

16. **What is transactional about submission creation?**  
    The service verifies the problem and inserts a `QUEUED` row plus audit event in one database transaction. A successful response means queued, not judged.

17. **What if a user submits code that prints forever?**  
    Output readers cap stdout/stderr and kill the process group when the cap is crossed; `timeout` and CPU limits also bound ordinary infinite loops.

18. **What did you benchmark?**  
    No honest end-to-end throughput benchmark is recorded yet: development is on Windows without MySQL/GCC, and the runner is Linux-only. I would report measurements only from the actual Linux target with worker/test-case details.
