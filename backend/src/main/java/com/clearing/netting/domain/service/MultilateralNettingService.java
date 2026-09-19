package com.clearing.netting.domain.service;

import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.MemberStatus;
import com.clearing.netting.domain.model.NetPosition;
import com.clearing.netting.domain.model.TradeObligation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure domain service: multilateral netting for a single currency.
 * Positive netAmount = receivable, negative = payable. Sum must be zero.
 *
 * <p>Split into {@link #validate} (校验) and {@link #calculate} (计算) so the
 * application layer can persist distinct server-side execution stages.
 */
public class MultilateralNettingService {

    public List<NetPosition> net(
            String runId,
            String currency,
            List<TradeObligation> openObligations,
            Map<String, Member> membersById) {
        validate(currency, openObligations, membersById);
        return calculate(runId, currency, openObligations);
    }

    /**
     * VALIDATING stage: fail fast on empty input, mixed currency, invalid
     * amounts, unknown or suspended members.
     */
    public void validate(
            String currency,
            List<TradeObligation> openObligations,
            Map<String, Member> membersById) {

        if (openObligations == null || openObligations.isEmpty()) {
            throw new DomainException("NO_OBLIGATIONS", "no OPEN obligations for settleDate/currency");
        }

        Set<String> currencies = new HashSet<>();
        for (TradeObligation o : openObligations) {
            currencies.add(o.getCurrency());
            if (!currency.equalsIgnoreCase(o.getCurrency())) {
                throw new DomainException("MIXED_CURRENCY", "mixed currency obligations are not allowed");
            }
            if (o.getAmount() == null || o.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new DomainException("INVALID_AMOUNT", "invalid amount on obligation " + o.getObligationId());
            }
        }
        if (currencies.size() > 1) {
            throw new DomainException("MIXED_CURRENCY", "mixed currency obligations are not allowed");
        }

        Set<String> involved = new HashSet<>();
        for (TradeObligation o : openObligations) {
            involved.add(o.getPayerMemberId());
            involved.add(o.getPayeeMemberId());
        }

        for (String memberId : involved) {
            Member member = membersById.get(memberId);
            if (member == null) {
                throw new DomainException("MEMBER_NOT_FOUND", "member not found: " + memberId);
            }
            if (member.getStatus() == MemberStatus.SUSPENDED) {
                throw new DomainException("SUSPENDED_MEMBER", "suspended member rejected: " + memberId);
            }
        }
    }

    /**
     * CALCULATING stage: aggregate bilateral obligations into multilateral net
     * positions and assert conservation (Σnet = 0).
     */
    public List<NetPosition> calculate(String runId, String currency, List<TradeObligation> openObligations) {
        Set<String> involved = new HashSet<>();
        for (TradeObligation o : openObligations) {
            involved.add(o.getPayerMemberId());
            involved.add(o.getPayeeMemberId());
        }

        Map<String, BigDecimal> nets = new java.util.HashMap<>();
        for (String memberId : involved) {
            nets.put(memberId, BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP));
        }

        for (TradeObligation o : openObligations) {
            BigDecimal amt = o.getAmount();
            // payer pays => negative net; payee receives => positive net
            nets.merge(o.getPayerMemberId(), amt.negate(), BigDecimal::add);
            nets.merge(o.getPayeeMemberId(), amt, BigDecimal::add);
        }

        BigDecimal sum = BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        List<NetPosition> positions = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : nets.entrySet()) {
            BigDecimal net = e.getValue().setScale(8, RoundingMode.HALF_UP);
            sum = sum.add(net);
            positions.add(NetPosition.of(runId, e.getKey(), currency.toUpperCase(), net));
        }

        if (sum.compareTo(BigDecimal.ZERO) != 0) {
            throw new DomainException("CONSERVATION_BROKEN", "ΣnetAmount must be 0, got " + sum);
        }

        return positions;
    }
}
