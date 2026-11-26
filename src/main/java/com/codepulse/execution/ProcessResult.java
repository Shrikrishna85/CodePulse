package com.codepulse.execution;

record ProcessResult(
        ExitReason exitReason,
        int exitCode,
        String stdout,
        String stderr,
        long elapsedMillis) {

    enum ExitReason {
        EXITED,
        TIME_LIMIT,
        MEMORY_LIMIT,
        OUTPUT_LIMIT
    }
}
