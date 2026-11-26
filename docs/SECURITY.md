# Security notes and threat model

## Threat model

CodePulse accepts source code from authenticated students, compiles it, and executes the result. Treat every submitted program as hostile. Other protected assets include session cookies, user account data, hidden test inputs/expected outputs, problem-authoring routes, and audit records.

Implemented controls include BCrypt password hashes, server-side sessions, CSRF tokens, role checks, login lockout after repeated bad passwords, security headers, input checks, source-size and per-user submission filters, bounded worker count, output capture limits, temporary working directories, Linux `timeout`/`ulimit`, and owner-only submission details. Hidden case inputs and expected outputs are not returned by the API.

## Important sandbox boundary

The current process runner is a **resource-control demo, not a security sandbox**. `timeout` can bound wall time; `ulimit` can constrain CPU time, virtual address space, output file size, and per-UID process count; bounded readers cap captured stdout/stderr; and process-group termination stops ordinary descendants on timeout/output overflow. A clean environment and temporary working directory reduce accidental state sharing.

These controls do **not** isolate the process from the host:

- The child runs as the application OS user and can still access any readable host files and attempt network connections.
- There is no namespace, syscall, device, filesystem, or network isolation; no seccomp, AppArmor, SELinux policy, cgroup, VM, gVisor, or Firecracker boundary.
- `ulimit -u` is scoped to the OS user, not one submission, so concurrent jobs share that limit.
- Java needs a minimum address-space allowance to start. The implementation uses a 512 MiB address-space floor and `-Xmx`/`-Xss`; actual resident memory is not measured or a strict whole-process cap.
- Exit-code based memory-limit detection is heuristic and varies by program and Linux kernel.
- `finally` cleanup handles normal failures, but power loss or a forced JVM kill can leave temporary directories behind.

**Do not expose this runner to arbitrary internet users or run it on a machine containing valuable credentials/data.** Process-level limits are not equivalent to containers, gVisor, seccomp, or a VM.

For a production service, use a dedicated unprivileged worker account with no secrets, deny network access, mount a minimal read-only filesystem, isolate each job with namespaces and cgroups, enforce syscall policy, keep the host patched, apply quotas, and audit/clean worker storage. A stronger sandbox must be independently reviewed and tested before accepting untrusted submissions.

## Application security details

- Student registration cannot choose a role. Admin is assigned only by controlled seed/setup data.
- State-changing API calls require the CSRF cookie value in `X-XSRF-TOKEN`.
- Session cookies are HttpOnly and SameSite=Lax. Terminate TLS and set the session cookie `Secure` flag in HTTPS deployment configuration.
- The in-memory token bucket is per process and resets on restart; it is not a distributed limiter.
- Audit events are append-only through the application service/API. Production DB grants should deny UPDATE/DELETE on `audit_log`.
- Demo credentials in `V3__demo_seed.sql` are public development-only values; replace/remove them before shared use.
