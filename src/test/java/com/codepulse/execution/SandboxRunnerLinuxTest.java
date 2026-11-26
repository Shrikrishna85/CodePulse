package com.codepulse.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
class SandboxRunnerLinuxTest {

    private SandboxRunner runner;

    @BeforeEach
    void setUp() {
        runner = new SandboxRunner(new LinuxProcessExecutor());
    }

    @Test
    void acceptsJavaProgramWithMatchingOutput() throws Exception {
        assertEquals(Verdict.ACCEPTED, runJava(
                "public class Main { public static void main(String[] args) { System.out.println(2 + 3); } }",
                "5\n",
                2_000).verdict());
    }

    @Test
    void reportsWrongAnswerForDifferentOutput() throws Exception {
        assertEquals(Verdict.WRONG_ANSWER, runJava(
                "public class Main { public static void main(String[] args) { System.out.println(4); } }",
                "5\n",
                2_000).verdict());
    }

    @Test
    void reportsCompilationError() throws Exception {
        assertEquals(Verdict.COMPILATION_ERROR, runJava(
                "public class Main { public static void main(String[] args) { missing(); } }",
                "",
                2_000).verdict());
    }

    @Test
    void reportsRuntimeError() throws Exception {
        assertEquals(Verdict.RUNTIME_ERROR, runJava(
                "public class Main { public static void main(String[] args) { throw new RuntimeException(); } }",
                "",
                2_000).verdict());
    }

    @Test
    void reportsWallClockTimeout() throws Exception {
        assertEquals(Verdict.TIME_LIMIT_EXCEEDED, runJava(
                "public class Main { public static void main(String[] args) throws Exception { Thread.sleep(10000); } }",
                "",
                500).verdict());
    }

    @Test
    void reportsOutputLimit() throws Exception {
        assertEquals(Verdict.OUTPUT_LIMIT_EXCEEDED, runJava(
                "public class Main { public static void main(String[] args) { while (true) System.out.println(\"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\"); } }",
                "",
                2_000).verdict());
    }

    @Test
    void compilesAndRunsCWithPipedInput() throws Exception {
        SandboxRunner.ExecutionSummary result = runner.judge(
                Language.C,
                "#include <stdio.h>\nint main(void) { int a, b; if (scanf(\"%d %d\", &a, &b) != 2) return 1; printf(\"%d\\n\", a + b); return 0; }",
                List.of(new SandboxRunner.TestCaseInput(1, 1, "7 8\n", "15\n", true)),
                2_000,
                256);

        assertEquals(Verdict.ACCEPTED, result.verdict());
    }

    private SandboxRunner.ExecutionSummary runJava(String source, String expectedOutput, int timeLimitMs)
            throws Exception {
        return runner.judge(
                Language.JAVA,
                source,
                List.of(new SandboxRunner.TestCaseInput(1, 1, "", expectedOutput, true)),
                timeLimitMs,
                256);
    }
}
