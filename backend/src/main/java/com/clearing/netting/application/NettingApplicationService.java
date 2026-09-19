package com.clearing.netting.application;

import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.NetPosition;
import com.clearing.netting.domain.model.NettingRun;
import com.clearing.netting.domain.model.NettingRunStatus;
import com.clearing.netting.domain.model.NettingStage;
import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;
import com.clearing.netting.domain.port.out.MemberRepositoryPort;
import com.clearing.netting.domain.port.out.NetPositionRepositoryPort;
import com.clearing.netting.domain.port.out.NettingRunRepositoryPort;
import com.clearing.netting.domain.port.out.ObligationRepositoryPort;
import com.clearing.netting.domain.service.MultilateralNettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class NettingApplicationService {

    private static final Logger log = LoggerFactory.getLogger(NettingApplicationService.class);

    /**
     * Short dwell per stage so progress is observable in the UI / over polls.
     * The work itself is near-instant for the demo data set.
     */
    private static final long STAGE_DWELL_MS = 500L;
    private static final int MAX_FAILURE_REASON_LENGTH = 500;

    private final NettingRunRepositoryPort runRepository;
    private final ObligationRepositoryPort obligationRepository;
    private final MemberRepositoryPort memberRepository;
    private final NetPositionRepositoryPort positionRepository;
    private final NettingRunStatusService statusService;
    private final NettingResultWriter resultWriter;
    private final ApplicationEventPublisher events;
    private final MultilateralNettingService nettingService;

    public NettingApplicationService(
            NettingRunRepositoryPort runRepository,
            ObligationRepositoryPort obligationRepository,
            MemberRepositoryPort memberRepository,
            NetPositionRepositoryPort positionRepository,
            NettingRunStatusService statusService,
            NettingResultWriter resultWriter,
            ApplicationEventPublisher events) {
        this.runRepository = runRepository;
        this.obligationRepository = obligationRepository;
        this.memberRepository = memberRepository;
        this.positionRepository = positionRepository;
        this.statusService = statusService;
        this.resultWriter = resultWriter;
        this.events = events;
        this.nettingService = new MultilateralNettingService();
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
     * Creates the run row in RUNNING/VALIDATING state, commits it, then hands
     * the work to the async worker. The returned run is a real persisted row,
     * so a refresh or a reopened page immediately shows 进行中.
     */
    public NettingRun execute(LocalDate settleDate, String currency) {
        if (settleDate == null) {
            throw new DomainException("INVALID_DATE", "settleDate is required");
        }
        if (currency == null || currency.isBlank()) {
            throw new DomainException("INVALID_CURRENCY", "currency is required");
        }
        String ccy = currency.trim().toUpperCase();

        for (NettingRun active : runRepository.findByStatusIn(
                List.of(NettingRunStatus.CREATED, NettingRunStatus.RUNNING))) {
            if (ccy.equals(active.getCurrency()) && settleDate.equals(active.getSettleDate())) {
                throw new DomainException("RUN_IN_PROGRESS",
                        "a netting run for " + ccy + " on " + settleDate + " is already in progress: "
                                + active.getRunId());
            }
        }

        NettingRun run = NettingRun.create(settleDate, ccy);
        run.markRunning();
        run = statusService.saveInNewTx(run);

        events.publishEvent(new NettingRunExecutionEvent(run.getRunId(), settleDate, ccy));
        return run;
    }

    /**
     * Async worker body: drives the run through the three persisted stages and
     * always lands on a terminal state (COMPLETED / FAILED). Runs outside the
     * request thread, without a wrapping transaction — each stage transition
     * commits independently via {@link NettingRunStatusService}.
     */
    public void processRun(NettingRunExecutionEvent event) {
        String runId = event.runId();
        try {
            // ---- Stage 1: VALIDATING (校验) ----
            statusService.markStageInNewTx(runId, NettingStage.VALIDATING);
            dwell();

            List<TradeObligation> opens =
                    obligationRepository.findOpenBySettleDateAndCurrency(event.settleDate(), event.currency());
            Set<String> memberIds = new HashSet<>();
            for (TradeObligation o : opens) {
                memberIds.add(o.getPayerMemberId());
                memberIds.add(o.getPayeeMemberId());
            }
            Map<String, Member> members = new HashMap<>();
            for (Member m : memberRepository.findByIds(memberIds)) {
                members.put(m.getMemberId(), m);
            }
            nettingService.validate(event.currency(), opens, members);

            // ---- Stage 2: CALCULATING (计算) ----
            statusService.markStageInNewTx(runId, NettingStage.CALCULATING);
            dwell();
            List<NetPosition> positions = nettingService.calculate(runId, event.currency(), opens);

            // ---- Stage 3: PERSISTING (落库) ----
            statusService.markStageInNewTx(runId, NettingStage.PERSISTING);
            dwell();
            resultWriter.persistResults(runId, opens, positions);

            statusService.markCompletedInNewTx(runId);
            log.info("netting run {} completed with {} position(s)", runId, positions.size());
        } catch (DomainException ex) {
            failRun(runId, ex.getMessage());
        } catch (RuntimeException ex) {
            String reason = ex.getMessage() == null ? "unexpected error" : ex.getMessage();
            log.error("netting run {} failed unexpectedly", runId, ex);
            failRun(runId, reason);
        }
    }

    private void failRun(String runId, String reason) {
        String safeReason = reason == null ? "unexpected error" : reason;
        if (safeReason.length() > MAX_FAILURE_REASON_LENGTH) {
            safeReason = safeReason.substring(0, MAX_FAILURE_REASON_LENGTH);
        }
        try {
            statusService.markFailedInNewTx(runId, safeReason);
        } catch (RuntimeException ex) {
            log.error("failed to persist FAILED state for run {}", runId, ex);
        }
    }

    private void dwell() {
        try {
            Thread.sleep(STAGE_DWELL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainException("RUN_INTERRUPTED", "netting run interrupted");
        }
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
