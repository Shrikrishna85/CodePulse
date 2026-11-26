package com.codepulse.submission;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import com.codepulse.execution.SandboxRunner;
import com.codepulse.execution.Verdict;
import com.codepulse.similarity.SimilarityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
public class SubmissionWorker implements org.springframework.context.SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubmissionWorker.class);
    private static final int WORKER_COUNT = 4;
    private static final long IDLE_POLL_MILLIS = 500;

    private final SubmissionQueueService queueService;
    private final SandboxRunner sandboxRunner;
    private final SimilarityService similarityService;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile List<Thread> workers = List.of();

    public SubmissionWorker(
            SubmissionQueueService queueService,
            SandboxRunner sandboxRunner,
            SimilarityService similarityService) {
        this.queueService = queueService;
        this.sandboxRunner = sandboxRunner;
        this.similarityService = similarityService;
    }

    @Override
    public void start() {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")) {
            LOGGER.warn("Submission execution is disabled: CodePulse runner requires Linux");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }

        queueService.recoverRunningSubmissions();
        List<Thread> startedWorkers = new ArrayList<>(WORKER_COUNT);
        for (int workerNumber = 1; workerNumber <= WORKER_COUNT; workerNumber++) {
            Thread worker = new Thread(() -> runWorker(), "codepulse-worker-" + workerNumber);
            worker.setDaemon(false);
            startedWorkers.add(worker);
            worker.start();
        }
        workers = List.copyOf(startedWorkers);
        LOGGER.info("Started {} submission workers", WORKER_COUNT);
    }

    private void runWorker() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Optional<SubmissionQueueService.ClaimedSubmission> next = queueService.claimNext();
                if (next.isEmpty()) {
                    Thread.sleep(IDLE_POLL_MILLIS);
                    continue;
                }
                execute(next.get());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (DataAccessException exception) {
                LOGGER.error("Database failure in submission worker; retrying shortly", exception);
                sleepAfterDatabaseFailure();
            }
        }
    }

    private void execute(SubmissionQueueService.ClaimedSubmission job) throws InterruptedException {
        try {
            SandboxRunner.ExecutionSummary result = sandboxRunner.judge(
                    job.language(),
                    job.sourceCode(),
                    queueService.getTestCases(job.problemId()),
                    job.timeLimitMs(),
                    job.memoryLimitMb());
            queueService.complete(job, result);
            if (result.verdict() == Verdict.ACCEPTED) {
                similarityService.compareAcceptedSubmission(job.id());
            }
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException exception) {
            LOGGER.error("Submission {} could not be executed", job.id(), exception);
            queueService.failInternally(job.id(), "Execution infrastructure failed. Please retry later.");
        }
    }

    private static void sleepAfterDatabaseFailure() {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        running.set(false);
        List<Thread> currentWorkers = workers;
        currentWorkers.forEach(Thread::interrupt);
        for (Thread worker : currentWorkers) {
            try {
                worker.join(3_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        workers = List.of();
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
