package com.codepulse.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VerdictMapperTest {

    @Test
    void mapsTimeoutAndMemoryLimitReasons() {
        assertEquals(Verdict.TIME_LIMIT_EXCEEDED, VerdictMapper.mapProcessResult(
                new ProcessResult(ProcessResult.ExitReason.TIME_LIMIT, 124, "", "", 1000), false));
        assertEquals(Verdict.MEMORY_LIMIT_EXCEEDED, VerdictMapper.mapProcessResult(
                new ProcessResult(ProcessResult.ExitReason.MEMORY_LIMIT, 137, "", "", 1000), false));
    }

    @Test
    void mapsNonzeroCompilationAndProgramExitCodesDifferently() {
        ProcessResult exitFailure = new ProcessResult(ProcessResult.ExitReason.EXITED, 1, "", "", 10);

        assertEquals(Verdict.COMPILATION_ERROR, VerdictMapper.mapProcessResult(exitFailure, true));
        assertEquals(Verdict.RUNTIME_ERROR, VerdictMapper.mapProcessResult(exitFailure, false));
    }
}
