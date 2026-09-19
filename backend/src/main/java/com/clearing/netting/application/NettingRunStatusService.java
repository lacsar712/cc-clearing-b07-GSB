package com.clearing.netting.application;

import com.clearing.netting.domain.model.NettingRun;
import com.clearing.netting.domain.model.NettingRunStatus;
import com.clearing.netting.domain.model.NettingStage;
import com.clearing.netting.domain.port.out.NettingRunRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Persists run lifecycle transitions in dedicated transactions so each stage
 * is durable independently of the async worker's main transaction — a refresh
 * or server restart always sees the last committed stage.
 */
@Service
public class NettingRunStatusService {

    private static final Logger log = LoggerFactory.getLogger(NettingRunStatusService.class);

    private final NettingRunRepositoryPort runRepository;

    public NettingRunStatusService(NettingRunRepositoryPort runRepository) {
        this.runRepository = runRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NettingRun saveInNewTx(NettingRun run) {
        return runRepository.save(run);
    }

    /**
     * Persist the current stage (VALIDATING / CALCULATING / PERSISTING) of a
     * running batch in its own transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NettingRun markStageInNewTx(String runId, NettingStage stage) {
        NettingRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("run disappeared: " + runId));
        run.updateStage(stage);
        return runRepository.save(run);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NettingRun markCompletedInNewTx(String runId) {
        NettingRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("run disappeared: " + runId));
        run.markCompleted();
        return runRepository.save(run);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NettingRun markFailedInNewTx(String runId, String reason) {
        NettingRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("run disappeared: " + runId));
        run.markFailed(reason);
        return runRepository.save(run);
    }

    /**
     * Runs interrupted mid-flight (process crash / deploy while RUNNING) cannot
     * finish in this JVM: mark them FAILED on startup so the list never shows a
     * permanently stuck "in progress" batch.
     */
    @Transactional
    public int recoverInterruptedRuns() {
        List<NettingRun> stuck =
                runRepository.findByStatusIn(List.of(NettingRunStatus.CREATED, NettingRunStatus.RUNNING));
        for (NettingRun run : stuck) {
            String at = run.getStage() == null ? "before first stage" : "at stage " + run.getStage();
            run.markFailed("server restart interrupted run " + at);
            runRepository.save(run);
            log.warn("recovered interrupted netting run {} ({})", run.getRunId(), at);
        }
        return stuck.size();
    }
}
