package com.codepulse.execution;

public final class VerdictMapper {

    private VerdictMapper() {}

    static Verdict mapProcessResult(ProcessResult result, boolean compiling) {
        return switch (result.exitReason()) {
            case TIME_LIMIT -> Verdict.TIME_LIMIT_EXCEEDED;
            case MEMORY_LIMIT -> Verdict.MEMORY_LIMIT_EXCEEDED;
            case OUTPUT_LIMIT -> Verdict.OUTPUT_LIMIT_EXCEEDED;
            case EXITED -> {
                if (compiling && result.exitCode() != 0) {
                    yield Verdict.COMPILATION_ERROR;
                }
                yield result.exitCode() == 0 ? Verdict.ACCEPTED : Verdict.RUNTIME_ERROR;
            }
        };
    }
}
