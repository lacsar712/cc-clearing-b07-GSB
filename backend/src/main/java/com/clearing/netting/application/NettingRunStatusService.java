package com.clearing.netting.application;

import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.NettingRun;
import com.clearing.netting.domain.model.NettingStage;
import com.clearing.netting.domain.port.out.NettingRunRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * All status/stage mutations run in their own committed transactions so that
 * progress is durable and queryable (via list/detail endpoints) while the
 * execution worker continues on a separate thread.
 */
@Service
public class NettingRunStatusService {

    static final int MAX_REASON_LENGTH = 500;

    private final NettingRunRepositoryPort runRepository;

    public NettingRunStatusService(NettingRunRepositoryPort runRepository) {
        this.runRepository = runRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NettingRun saveInNewTx(NettingRun run) {
        return runRepository.save(run);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStageInNewTx(String runId, NettingStage stage) {
        NettingRun run = requireRun(runId);
        run.updateStage(stage);
        runRepository.save(run);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeInNewTx(String runId) {
        NettingRun run = requireRun(runId);
        run.markCompleted();
        runRepository.save(run);
    }

    /** Marks the run FAILED with the persisted server-side failure reason. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failInNewTx(String runId, String reason) {
        NettingRun run = requireRun(runId);
        run.markFailed(truncate(reason));
        runRepository.save(run);
    }

    /** Recovery path: batches interrupted by a process restart can never finish. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abandonInNewTx(NettingRun run, String reason) {
        run.markAbandoned(truncate(reason));
        runRepository.save(run);
    }

    private NettingRun requireRun(String runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new DomainException("RUN_NOT_FOUND", "netting run not found: " + runId));
    }

    private static String truncate(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unexpected error";
        }
        return reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
    }
}
