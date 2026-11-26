package com.codepulse.execution;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class SandboxRunner {

    private static final int MAX_SOURCE_BYTES = 64 * 1024;
    private static final int MAX_ERROR_SNIPPET_CHARS = 2_000;
    private static final int COMPILATION_TIMEOUT_MS = 20_000;

    private final LinuxProcessExecutor processExecutor;

    public SandboxRunner(LinuxProcessExecutor processExecutor) {
        this.processExecutor = processExecutor;
    }

    public ExecutionSummary judge(
            Language language,
            String sourceCode,
            List<TestCaseInput> testCases,
            int timeLimitMs,
            int memoryLimitMb) throws IOException, InterruptedException {
        if (language == null || sourceCode == null || testCases == null || testCases.isEmpty()) {
            throw new IllegalArgumentException("Language, source code, and test cases are required");
        }
        if (sourceCode.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("Source code exceeds 64 KiB");
        }
        if (timeLimitMs < 250 || timeLimitMs > 30_000 || memoryLimitMb < 32 || memoryLimitMb > 1024) {
            throw new IllegalArgumentException("Unsupported execution limits");
        }

        Path workDirectory = Files.createTempDirectory("codepulse-");
        try {
            List<String> compileCommand = writeSource(language, workDirectory, sourceCode);
            ProcessResult compilation = processExecutor.execute(
                    workDirectory,
                    compileCommand,
                    "",
                    COMPILATION_TIMEOUT_MS,
                    Math.max(memoryLimitMb, 512));
            if (compilation.exitReason() != ProcessResult.ExitReason.EXITED || compilation.exitCode() != 0) {
                Verdict verdict = compilation.exitReason() == ProcessResult.ExitReason.MEMORY_LIMIT
                        ? Verdict.MEMORY_LIMIT_EXCEEDED
                        : Verdict.COMPILATION_ERROR;
                String snippet = truncate(compilation.stderr().isBlank() ? compilation.stdout() : compilation.stderr());
                return compilationFailure(verdict, snippet, testCases, compilation.elapsedMillis());
            }

            List<CaseResult> results = new ArrayList<>(testCases.size());
            for (TestCaseInput testCase : testCases) {
                ProcessResult execution = processExecutor.execute(
                        workDirectory,
                        runCommand(language, memoryLimitMb),
                        testCase.stdin(),
                        timeLimitMs,
                        language == Language.JAVA ? Math.max(memoryLimitMb, 512) : memoryLimitMb);
                Verdict verdict = VerdictMapper.mapProcessResult(execution, false);
                if (verdict == Verdict.ACCEPTED
                        && !OutputChecker.matches(testCase.expectedOutput(), execution.stdout())) {
                    verdict = Verdict.WRONG_ANSWER;
                }
                String feedback = testCase.sample()
                        ? truncate(execution.stderr().isBlank() ? execution.stdout() : execution.stderr())
                        : null;
                results.add(new CaseResult(testCase.id(), testCase.ordinal(), testCase.sample(), verdict,
                        execution.elapsedMillis(), feedback));
            }

            Verdict finalVerdict = results.stream()
                    .map(CaseResult::verdict)
                    .filter(verdict -> verdict != Verdict.ACCEPTED)
                    .findFirst()
                    .orElse(Verdict.ACCEPTED);
            return new ExecutionSummary(finalVerdict, results, null);
        } finally {
            deleteTree(workDirectory);
        }
    }

    private static List<String> writeSource(Language language, Path workDirectory, String sourceCode)
            throws IOException {
        return switch (language) {
            case JAVA -> {
                Files.writeString(workDirectory.resolve("Main.java"), sourceCode);
                yield List.of("javac", "-J-Xmx384m", "-J-Xss256k", "-encoding", "UTF-8", "Main.java");
            }
            case C -> {
                Files.writeString(workDirectory.resolve("main.c"), sourceCode);
                yield List.of("gcc", "-std=c17", "-O2", "-pipe", "-o", "program", "main.c");
            }
        };
    }

    private static List<String> runCommand(Language language, int memoryLimitMb) {
        return switch (language) {
            case JAVA -> {
                int heapMb = Math.max(16, memoryLimitMb - 64);
                yield List.of("java", "-Xmx" + heapMb + "m", "-Xss256k", "-cp", ".", "Main");
            }
            case C -> List.of("./program");
        };
    }

    private static ExecutionSummary compilationFailure(
            Verdict verdict,
            String errorSnippet,
            List<TestCaseInput> testCases,
            long compilationMillis) {
        List<CaseResult> results = testCases.stream()
                .map(testCase -> new CaseResult(
                        testCase.id(),
                        testCase.ordinal(),
                        testCase.sample(),
                        verdict,
                        compilationMillis,
                        null))
                .toList();
        return new ExecutionSummary(verdict, results, errorSnippet);
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= MAX_ERROR_SNIPPET_CHARS) {
            return text;
        }
        return text.substring(0, MAX_ERROR_SNIPPET_CHARS);
    }

    private static void deleteTree(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public record TestCaseInput(long id, int ordinal, String stdin, String expectedOutput, boolean sample) {}

    public record CaseResult(
            long testCaseId,
            int ordinal,
            boolean sample,
            Verdict verdict,
            long executionTimeMs,
            String feedbackSnippet) {}

    public record ExecutionSummary(Verdict verdict, List<CaseResult> testResults, String errorSnippet) {}
}
