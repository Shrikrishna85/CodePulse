package com.codepulse.execution;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

@Component
public class LinuxProcessExecutor {

    private static final int STDOUT_LIMIT_BYTES = 64 * 1024;
    private static final int STDERR_LIMIT_BYTES = 16 * 1024;
    private static final int MAX_PROCESS_COUNT = 64;

    public ProcessResult execute(
            Path workDirectory,
            List<String> command,
            String stdin,
            int timeoutMillis,
            int memoryLimitMb) throws IOException, InterruptedException {
        requireLinux();
        if (timeoutMillis < 1 || memoryLimitMb < 32 || command.isEmpty()) {
            throw new IllegalArgumentException("Invalid process execution limits or command");
        }

        List<String> launchCommand = List.of("/usr/bin/setsid", "/bin/bash", "-c", buildScript(command, timeoutMillis, memoryLimitMb));
        ProcessBuilder processBuilder = new ProcessBuilder(launchCommand);
        processBuilder.directory(workDirectory.toFile());
        processBuilder.environment().clear();
        processBuilder.environment().put("PATH", "/usr/bin:/bin");
        processBuilder.environment().put("HOME", workDirectory.toString());
        processBuilder.environment().put("TMPDIR", workDirectory.toString());
        processBuilder.environment().put("LANG", "C.UTF-8");
        processBuilder.environment().put("LC_ALL", "C.UTF-8");

        long startedAt = System.nanoTime();
        Process process = processBuilder.start();
        long processGroupId = process.pid();
        AtomicBoolean outputLimitExceeded = new AtomicBoolean();
        AtomicReference<IOException> inputFailure = new AtomicReference<>();
        OutputCapture stdout = new OutputCapture(
                "codepulse-stdout",
                process.getInputStream(),
                STDOUT_LIMIT_BYTES,
                outputLimitExceeded,
                () -> killProcessGroup(processGroupId, process));
        OutputCapture stderr = new OutputCapture(
                "codepulse-stderr",
                process.getErrorStream(),
                STDERR_LIMIT_BYTES,
                outputLimitExceeded,
                () -> killProcessGroup(processGroupId, process));
        Thread stdinWriter = new Thread(
                () -> writeStdin(process, stdin, inputFailure),
                "codepulse-stdin");
        stdinWriter.setDaemon(true);
        stdout.start();
        stderr.start();
        stdinWriter.start();

        boolean finished = false;
        try {
            finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                killProcessGroup(processGroupId, process);
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } finally {
            if (process.isAlive()) {
                killProcessGroup(processGroupId, process);
                process.destroyForcibly();
            }
            process.getOutputStream().close();
        }

        stdinWriter.join(2_000);
        stdout.join(2_000);
        stderr.join(2_000);
        if (stdinWriter.isAlive() || stdout.isAlive() || stderr.isAlive()) {
            throw new IOException("Process I/O did not stop after the child process exited");
        }
        if (inputFailure.get() != null && process.exitValue() == 0) {
            throw new IOException("Failed to deliver test input to the child process", inputFailure.get());
        }
        if (stdout.readFailure() != null) {
            throw new IOException("Failed to capture child stdout", stdout.readFailure());
        }
        if (stderr.readFailure() != null) {
            throw new IOException("Failed to capture child stderr", stderr.readFailure());
        }

        int exitCode = process.exitValue();
        ProcessResult.ExitReason reason;
        if (outputLimitExceeded.get()) {
            reason = ProcessResult.ExitReason.OUTPUT_LIMIT;
        } else if (!finished || exitCode == 124 || exitCode == 152) {
            reason = ProcessResult.ExitReason.TIME_LIMIT;
        } else if (exitCode == 137) {
            reason = ProcessResult.ExitReason.MEMORY_LIMIT;
        } else {
            reason = ProcessResult.ExitReason.EXITED;
        }

        return new ProcessResult(
                reason,
                exitCode,
                stdout.text(),
                stderr.text(),
                Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
    }

    private static void writeStdin(Process process, String stdin, AtomicReference<IOException> failure) {
        try (var output = process.getOutputStream()) {
            output.write(stdin.getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException exception) {
            failure.set(exception);
        }
    }

    private static String buildScript(List<String> command, int timeoutMillis, int memoryLimitMb) {
        int cpuSeconds = Math.max(1, (int) Math.ceil(timeoutMillis / 1000.0));
        long memoryLimitKb = (long) memoryLimitMb * 1024;
        long fileSizeLimitBlocks = (STDOUT_LIMIT_BYTES + STDERR_LIMIT_BYTES) / 512;
        String timeout = String.format(Locale.ROOT, "%.3fs", timeoutMillis / 1000.0 + 2.0);
        List<String> quotedCommand = new ArrayList<>(command.size());
        for (String argument : command) {
            quotedCommand.add("'" + argument.replace("'", "'\\''") + "'");
        }
        return "ulimit -c 0; "
                + "ulimit -t " + cpuSeconds + "; "
                + "ulimit -v " + memoryLimitKb + "; "
                + "ulimit -f " + fileSizeLimitBlocks + "; "
                + "ulimit -u " + MAX_PROCESS_COUNT + "; "
                + "exec /usr/bin/timeout --foreground --signal=TERM --kill-after=1s "
                + timeout + " "
                + String.join(" ", quotedCommand);
    }

    private static void killProcessGroup(long processGroupId, Process process) {
        try {
            Process killer = new ProcessBuilder(
                            "/bin/bash",
                            "-c",
                            "kill -KILL -- -" + processGroupId + " 2>/dev/null || true")
                    .start();
            if (!killer.waitFor(1, TimeUnit.SECONDS)) {
                killer.destroyForcibly();
            }
        } catch (IOException exception) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new UncheckedIOException("Could not terminate the execution process group", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new IllegalStateException("Interrupted while terminating execution process group", exception);
        }
    }

    private static void requireLinux() {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")) {
            throw new UnsupportedOperationException("CodePulse execution requires Linux process limits and tools");
        }
    }

    private static final class OutputCapture extends Thread {

        private final InputStream input;
        private final int limitBytes;
        private final AtomicBoolean outputLimitExceeded;
        private final Runnable onLimit;
        private final ByteArrayOutputStream captured;
        private volatile IOException readFailure;

        private OutputCapture(
                String name,
                InputStream input,
                int limitBytes,
                AtomicBoolean outputLimitExceeded,
                Runnable onLimit) {
            super(name);
            this.input = input;
            this.limitBytes = limitBytes;
            this.outputLimitExceeded = outputLimitExceeded;
            this.onLimit = onLimit;
            this.captured = new ByteArrayOutputStream(Math.min(limitBytes, 8192));
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] buffer = new byte[4096];
            try (input) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    int remaining = limitBytes - captured.size();
                    int keep = Math.min(read, Math.max(remaining, 0));
                    if (keep > 0) {
                        captured.write(buffer, 0, keep);
                    }
                    if (read > keep) {
                        outputLimitExceeded.set(true);
                        onLimit.run();
                        return;
                    }
                }
            } catch (IOException exception) {
                readFailure = exception;
            }
        }

        private String text() {
            return captured.toString(StandardCharsets.UTF_8);
        }

        private IOException readFailure() {
            return readFailure;
        }
    }
}
