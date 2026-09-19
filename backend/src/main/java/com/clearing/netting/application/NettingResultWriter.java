package com.clearing.netting.application;

import com.clearing.netting.domain.model.NetPosition;
import com.clearing.netting.domain.model.TradeObligation;
import com.clearing.netting.domain.port.out.NetPositionRepositoryPort;
import com.clearing.netting.domain.port.out.ObligationRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Writes the netting outcome (NETTED obligations + net positions) in one
 * transaction — the PERSISTING stage is all-or-nothing.
 */
@Service
public class NettingResultWriter {

    private final ObligationRepositoryPort obligationRepository;
    private final NetPositionRepositoryPort positionRepository;

    public NettingResultWriter(
            ObligationRepositoryPort obligationRepository,
            NetPositionRepositoryPort positionRepository) {
        this.obligationRepository = obligationRepository;
        this.positionRepository = positionRepository;
    }

    @Transactional
    public void persistResults(String runId, List<TradeObligation> opens, List<NetPosition> positions) {
        for (TradeObligation o : opens) {
            o.markNetted(runId);
        }
        obligationRepository.saveAll(opens);
        positionRepository.saveAll(positions);
    }
}
