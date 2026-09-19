package com.clearing.netting.application;

import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.NetPosition;
import com.clearing.netting.domain.model.NettingRun;
import com.clearing.netting.domain.model.TradeObligation;
import com.clearing.netting.domain.port.out.NetPositionRepositoryPort;
import com.clearing.netting.domain.port.out.NettingRunRepositoryPort;
import com.clearing.netting.domain.port.out.ObligationRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Persists the outcome of a run in a single atomic transaction:
 * obligations -> NETTED, net positions inserted, run -> COMPLETED.
 * Any failure rolls the whole commit back and the worker marks the run FAILED.
 */
@Service
public class NettingResultWriter {

    private final ObligationRepositoryPort obligationRepository;
    private final NetPositionRepositoryPort positionRepository;
    private final NettingRunRepositoryPort runRepository;

    public NettingResultWriter(
            ObligationRepositoryPort obligationRepository,
            NetPositionRepositoryPort positionRepository,
            NettingRunRepositoryPort runRepository) {
        this.obligationRepository = obligationRepository;
        this.positionRepository = positionRepository;
        this.runRepository = runRepository;
    }

    @Transactional
    public void writeAndComplete(String runId, LocalDate settleDate, String currency, List<NetPosition> positions) {
        List<TradeObligation> opens = obligationRepository.findOpenBySettleDateAndCurrency(settleDate, currency);
        if (opens.isEmpty()) {
            // Another run already netted these obligations (duplicate/concurrent submit).
            // Fail before writing anything rather than inserting duplicate positions.
            throw new DomainException("NO_OBLIGATIONS",
                    "no OPEN obligations left for settleDate/currency at persist time");
        }
        for (TradeObligation o : opens) {
            o.markNetted(runId);
        }
        obligationRepository.saveAll(opens);
        positionRepository.saveAll(positions);

        NettingRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("netting run vanished: " + runId));
        run.markCompleted();
        runRepository.save(run);
    }
}
