package com.clearing.netting.application;

import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.NetPosition;
import com.clearing.netting.domain.model.NettingStage;
import com.clearing.netting.domain.model.TradeObligation;
import com.clearing.netting.domain.port.out.MemberRepositoryPort;
import com.clearing.netting.domain.port.out.ObligationRepositoryPort;
import com.clearing.netting.domain.service.MultilateralNettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drives a netting run on a background thread after the run row has been
 * committed as RUNNING/VALIDATING. Every phase transition is persisted in its
 * own transaction, so progress survives page refreshes and restarts.
 */
@Service
public class NettingRunExecutionWorker {

    private static final Logger log = LoggerFactory.getLogger(NettingRunExecutionWorker.class);

    private final ObligationRepositoryPort obligationRepository;
    private final MemberRepositoryPort memberRepository;
    private final NettingRunStatusService statusService;
    private final NettingResultWriter resultWriter;
    private final MultilateralNettingService nettingService = new MultilateralNettingService();

    /**
     * Pause between persisted phases so clients polling at ~1s can actually
     * observe VALIDATING/CALCULATING/PERSISTING. Set to 0 to disable.
     * Every state remains real and server-persisted; this only paces the work.
     */
    private final long stageDelayMs;

    public NettingRunExecutionWorker(
            ObligationRepositoryPort obligationRepository,
            MemberRepositoryPort memberRepository,
            NettingRunStatusService statusService,
            NettingResultWriter resultWriter,
            @Value("${app.netting.stage-delay-ms:600}") long stageDelayMs) {
        this.obligationRepository = obligationRepository;
        this.memberRepository = memberRepository;
        this.statusService = statusService;
        this.resultWriter = resultWriter;
        this.stageDelayMs = stageDelayMs;
    }

    @Async("nettingRunExecutor")
    public void run(String runId, LocalDate settleDate, String currency) {
        try {
            // 阶段 1：校验（校验打开义务与会员状态）。批次落库时已处于 VALIDATING，
            // 先停留一个节拍，保证快速失败的场景下前端也能观察到“校验中”。
            delayBetweenStages();
            List<TradeObligation> opens = obligationRepository.findOpenBySettleDateAndCurrency(settleDate, currency);
            Set<String> memberIds = new HashSet<>();
            for (TradeObligation o : opens) {
                memberIds.add(o.getPayerMemberId());
                memberIds.add(o.getPayeeMemberId());
            }
            Map<String, Member> members = new HashMap<>();
            for (Member m : memberRepository.findByIds(memberIds)) {
                members.put(m.getMemberId(), m);
            }
            nettingService.validate(currency, opens, members);

            // Hold VALIDATING briefly, then advance to 计算
            delayBetweenStages();
            statusService.updateStageInNewTx(runId, NettingStage.CALCULATING);
            List<NetPosition> positions = nettingService.calculate(runId, currency, opens);

            // Hold CALCULATING briefly, then advance to 落库
            delayBetweenStages();
            statusService.updateStageInNewTx(runId, NettingStage.PERSISTING);
            delayBetweenStages();
            resultWriter.writeAndComplete(runId, settleDate, currency, positions);
        } catch (DomainException ex) {
            log.warn("Netting run {} failed at domain validation: {}", runId, ex.getMessage());
            statusService.failInNewTx(runId, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Netting run {} failed unexpectedly", runId, ex);
            statusService.failInNewTx(runId, ex.getMessage() == null ? "unexpected error" : ex.getMessage());
        }
    }

    private void delayBetweenStages() {
        if (stageDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(stageDelayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("netting run interrupted: " + ie.getMessage());
        }
    }
}
