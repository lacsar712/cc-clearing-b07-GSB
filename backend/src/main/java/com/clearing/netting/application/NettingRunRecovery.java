package com.clearing.netting.application;

import com.clearing.netting.domain.model.NettingRun;
import com.clearing.netting.domain.port.out.NettingRunRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Recovers batches interrupted by a server restart: their worker thread is gone
 * so they can never reach a terminal state. Mark them FAILED with an explicit
 * reason instead of leaving a stuck RUNNING that the UI would poll forever.
 */
@Component
public class NettingRunRecovery {

    private static final Logger log = LoggerFactory.getLogger(NettingRunRecovery.class);
    static final String RESTART_REASON = "server restarted before run finished; execution interrupted";

    private final NettingRunRepositoryPort runRepository;
    private final NettingRunStatusService statusService;

    public NettingRunRecovery(NettingRunRepositoryPort runRepository, NettingRunStatusService statusService) {
        this.runRepository = runRepository;
        this.statusService = statusService;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    @Transactional(readOnly = true)
    public void recoverInterruptedRuns() {
        List<NettingRun> unfinished = runRepository.findUnfinished();
        for (NettingRun run : unfinished) {
            log.warn("Recovering interrupted netting run {} (status={})", run.getRunId(), run.getStatus());
            statusService.abandonInNewTx(run, RESTART_REASON);
        }
    }
}
