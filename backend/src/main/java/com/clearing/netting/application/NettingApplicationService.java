package com.clearing.netting.application;

import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.NetPosition;
import com.clearing.netting.domain.model.NettingRun;
import com.clearing.netting.domain.model.NettingRunStatus;
import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;
import com.clearing.netting.domain.port.out.NetPositionRepositoryPort;
import com.clearing.netting.domain.port.out.NettingRunRepositoryPort;
import com.clearing.netting.domain.port.out.ObligationRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class NettingApplicationService {

    private final NettingRunRepositoryPort runRepository;
    private final ObligationRepositoryPort obligationRepository;
    private final NetPositionRepositoryPort positionRepository;
    private final NettingRunStatusService statusService;
    private final NettingRunExecutionWorker executionWorker;

    public NettingApplicationService(
            NettingRunRepositoryPort runRepository,
            ObligationRepositoryPort obligationRepository,
            NetPositionRepositoryPort positionRepository,
            NettingRunStatusService statusService,
            NettingRunExecutionWorker executionWorker) {
        this.runRepository = runRepository;
        this.obligationRepository = obligationRepository;
        this.positionRepository = positionRepository;
        this.statusService = statusService;
        this.executionWorker = executionWorker;
    }

    @Transactional(readOnly = true)
    public List<NettingRun> listRuns() {
        return runRepository.findAllOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public NettingRun getRun(String runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new DomainException("RUN_NOT_FOUND", "netting run not found: " + runId));
    }

    @Transactional(readOnly = true)
    public List<NetPosition> getPositions(String runId) {
        getRun(runId);
        return positionRepository.findByRunId(runId);
    }

    @Transactional(readOnly = true)
    public List<TradeObligation> getRunObligations(String runId) {
        getRun(runId);
        return obligationRepository.findByNettingRunId(runId);
    }

    /**
     * Creates the batch, commits it as RUNNING in the VALIDATING phase, then
     * hands the actual work to the background worker. Returns immediately so
     * the client polls the persisted status; nothing is faked client-side.
     */
    @Transactional
    public NettingRun execute(LocalDate settleDate, String currency) {
        if (settleDate == null) {
            throw new DomainException("INVALID_DATE", "settleDate is required");
        }
        if (currency == null || currency.isBlank()) {
            throw new DomainException("INVALID_CURRENCY", "currency is required");
        }
        String ccy = currency.trim().toUpperCase();

        for (NettingRun unfinished : runRepository.findUnfinished()) {
            if (unfinished.getSettleDate().equals(settleDate) && unfinished.getCurrency().equals(ccy)) {
                throw new DomainException("RUN_ALREADY_RUNNING",
                        "a netting run is already in progress for " + ccy + " on " + settleDate
                                + ": " + unfinished.getRunId());
            }
        }

        NettingRun run = NettingRun.create(settleDate, ccy);
        run.markRunning();
        // Commit the RUNNING/VALIDATING row in its own transaction BEFORE handing
        // off to the worker, so the background thread always sees a persisted run.
        NettingRun saved = statusService.saveInNewTx(run);

        executionWorker.run(saved.getRunId(), settleDate, ccy);
        return saved;
    }

    @Transactional
    public NettingRun settle(String runId) {
        NettingRun run = getRun(runId);
        if (run.getStatus() != NettingRunStatus.COMPLETED) {
            throw new DomainException("INVALID_STATE", "only COMPLETED runs can be settled");
        }
        List<TradeObligation> obligations = obligationRepository.findByNettingRunId(runId);
        if (obligations.isEmpty()) {
            throw new DomainException("NO_OBLIGATIONS", "no obligations linked to run");
        }
        for (TradeObligation o : obligations) {
            if (o.getStatus() == ObligationStatus.NETTED) {
                o.markSettled();
            } else if (o.getStatus() != ObligationStatus.SETTLED) {
                throw new DomainException("INVALID_STATE", "obligation not NETTED: " + o.getObligationId());
            }
        }
        obligationRepository.saveAll(obligations);
        return run;
    }
}
