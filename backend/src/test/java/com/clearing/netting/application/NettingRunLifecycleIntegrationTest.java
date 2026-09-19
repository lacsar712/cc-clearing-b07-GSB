package com.clearing.netting.application;

import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.NetPosition;
import com.clearing.netting.domain.model.NettingRun;
import com.clearing.netting.domain.model.NettingRunStatus;
import com.clearing.netting.domain.model.NettingStage;
import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;
import com.clearing.netting.domain.port.out.NettingRunRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class NettingRunLifecycleIntegrationTest {

    private static final LocalDate SETTLE = LocalDate.of(2026, 9, 19);

    @Autowired
    private NettingApplicationService nettingService;
    @Autowired
    private MemberApplicationService memberService;
    @Autowired
    private ObligationApplicationService obligationService;
    @Autowired
    private NettingRunStatusService statusService;
    @Autowired
    private NettingRunRepositoryPort runRepository;

    @Test
    void runIsPersistedRunningThenCompletesWithStagedLifecycle() throws Exception {
        seedTriangleObligations();

        NettingRun started = nettingService.execute(SETTLE, "USD");
        // Row is already in the database in RUNNING/VALIDATING when the call returns
        assertEquals(NettingRunStatus.RUNNING, started.getStatus());
        assertEquals(NettingStage.VALIDATING, started.getStage());
        assertNotNull(started.getStageUpdatedAt());
        NettingRun persisted = nettingService.getRun(started.getRunId());
        assertEquals(NettingRunStatus.RUNNING, persisted.getStatus());

        NettingRun terminal = awaitTerminal(started.getRunId(), Duration.ofSeconds(15));
        assertEquals(NettingRunStatus.COMPLETED, terminal.getStatus());
        assertNull(terminal.getStage());
        assertNull(terminal.getFailureReason());

        List<NetPosition> positions = nettingService.getPositions(started.getRunId());
        assertFalse(positions.isEmpty());
        BigDecimal sum = positions.stream().map(NetPosition::getNetAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(BigDecimal.ZERO));

        List<TradeObligation> linked = nettingService.getRunObligations(started.getRunId());
        assertTrue(linked.stream().allMatch(o -> o.getStatus() == ObligationStatus.NETTED));
    }

    @Test
    void secondExecuteWhileRunningIsRejectedWithConflict() {
        seedTriangleObligations();
        NettingRun started = nettingService.execute(SETTLE, "EUR");
        assertEquals(NettingRunStatus.RUNNING, started.getStatus());

        DomainException ex = assertThrows(DomainException.class, () -> nettingService.execute(SETTLE, "EUR"));
        assertEquals("RUN_IN_PROGRESS", ex.getCode());
    }

    @Test
    void runWithoutOpenObligationsFailsWithPersistedReason() throws Exception {
        // no obligations seeded for CNY
        NettingRun started = nettingService.execute(SETTLE, "CNY");
        assertEquals(NettingRunStatus.RUNNING, started.getStatus());

        NettingRun terminal = awaitTerminal(started.getRunId(), Duration.ofSeconds(15));
        assertEquals(NettingRunStatus.FAILED, terminal.getStatus());
        assertNull(terminal.getStage());
        assertNotNull(terminal.getFailureReason());
        assertTrue(terminal.getFailureReason().contains("no OPEN obligations"),
                "failureReason should be the domain reason, got: " + terminal.getFailureReason());
        assertTrue(nettingService.getPositions(started.getRunId()).isEmpty());
    }

    @Test
    void interruptedRunningRunIsRecoveredAsFailedOnStartup() {
        NettingRun stuck = new NettingRun(
                "stuck-run-1", SETTLE, "USD",
                NettingRunStatus.RUNNING, Instant.now(), null,
                NettingStage.PERSISTING, Instant.now());
        runRepository.save(stuck);

        int recovered = statusService.recoverInterruptedRuns();
        assertTrue(recovered >= 1);

        NettingRun after = nettingService.getRun("stuck-run-1");
        assertEquals(NettingRunStatus.FAILED, after.getStatus());
        assertNotNull(after.getFailureReason());
        assertTrue(after.getFailureReason().contains("server restart interrupted run"));
        assertTrue(after.getFailureReason().contains("PERSISTING"));
    }

    private void seedTriangleObligations() {
        Member a = memberService.createMember("IT Alpha");
        Member b = memberService.createMember("IT Beta");
        Member c = memberService.createMember("IT Gamma");
        LocalDate trade = SETTLE.minusDays(1);
        obligationService.create(a.getMemberId(), b.getMemberId(), "USD", new BigDecimal("100"), trade, SETTLE);
        obligationService.create(b.getMemberId(), c.getMemberId(), "USD", new BigDecimal("60"), trade, SETTLE);
        obligationService.create(c.getMemberId(), a.getMemberId(), "USD", new BigDecimal("40"), trade, SETTLE);
        // Use distinct currency for the in-progress guard test so it never clashes with USD rows
        obligationService.create(a.getMemberId(), b.getMemberId(), "EUR", new BigDecimal("10"), trade, SETTLE);
        obligationService.create(b.getMemberId(), a.getMemberId(), "EUR", new BigDecimal("10"), trade, SETTLE);
    }

    private NettingRun awaitTerminal(String runId, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        NettingRun run = nettingService.getRun(runId);
        while ((run.getStatus() == NettingRunStatus.RUNNING || run.getStatus() == NettingRunStatus.CREATED)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            run = nettingService.getRun(runId);
        }
        assertTrue(run.getStatus() == NettingRunStatus.COMPLETED || run.getStatus() == NettingRunStatus.FAILED,
                "run did not reach terminal state, last=" + run.getStatus() + "/" + run.getStage());
        return run;
    }
}
