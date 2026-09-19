package com.clearing.netting.adapter.in.web;

import com.clearing.netting.adapter.in.web.auth.AuthContext;
import com.clearing.netting.application.NettingApplicationService;
import com.clearing.netting.domain.model.NetPosition;
import com.clearing.netting.domain.model.NettingRun;
import com.clearing.netting.domain.model.NettingRunStatus;
import com.clearing.netting.domain.model.NettingStage;
import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/netting-runs")
public class NettingRunController {

    private final NettingApplicationService nettingService;

    public NettingRunController(NettingApplicationService nettingService) {
        this.nettingService = nettingService;
    }

    @GetMapping
    public List<RunResponse> list() {
        AuthContext.require();
        return nettingService.listRuns().stream().map(RunResponse::from).collect(Collectors.toList());
    }

    /**
     * Starts a run and returns 202 as soon as the RUNNING batch is persisted.
     * The client tracks progress via GET /{id}; success/failure is decided by
     * the persisted status, never by this response.
     */
    @PostMapping
    public ResponseEntity<RunResponse> execute(@Valid @RequestBody ExecuteRequest request) {
        AuthContext.requireOperator();
        NettingRun run = nettingService.execute(request.settleDate(), request.currency());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(RunResponse.from(run));
    }

    @GetMapping("/{id}")
    public RunDetailResponse get(@PathVariable("id") String id) {
        AuthContext.require();
        NettingRun run = nettingService.getRun(id);
        List<TradeObligation> obligations = nettingService.getRunObligations(id);
        List<NetPosition> positions = nettingService.getPositions(id);
        return new RunDetailResponse(
                RunResponse.from(run),
                obligations.stream().map(ObligationBrief::from).collect(Collectors.toList()),
                positions.stream().map(PositionResponse::from).collect(Collectors.toList()),
                sumNet(positions));
    }

    @GetMapping("/{id}/positions")
    public List<PositionResponse> positions(@PathVariable("id") String id) {
        AuthContext.require();
        return nettingService.getPositions(id).stream().map(PositionResponse::from).collect(Collectors.toList());
    }

    @PostMapping("/{id}/settle")
    public RunResponse settle(@PathVariable("id") String id) {
        AuthContext.requireOperator();
        return RunResponse.from(nettingService.settle(id));
    }

    private BigDecimal sumNet(List<NetPosition> positions) {
        return positions.stream()
                .map(NetPosition::getNetAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public record ExecuteRequest(@NotNull LocalDate settleDate, @NotBlank String currency) {
    }

    public record RunResponse(
            String runId,
            LocalDate settleDate,
            String currency,
            NettingRunStatus status,
            NettingStage stage,
            Instant createdAt,
            String failureReason) {
        static RunResponse from(NettingRun r) {
            return new RunResponse(
                    r.getRunId(),
                    r.getSettleDate(),
                    r.getCurrency(),
                    r.getStatus(),
                    r.getStage(),
                    r.getCreatedAt(),
                    r.getFailureReason());
        }
    }

    public record PositionResponse(
            String positionId,
            String runId,
            String memberId,
            String currency,
            BigDecimal netAmount) {
        static PositionResponse from(NetPosition p) {
            return new PositionResponse(
                    p.getPositionId(),
                    p.getRunId(),
                    p.getMemberId(),
                    p.getCurrency(),
                    p.getNetAmount());
        }
    }

    public record ObligationBrief(
            String obligationId,
            String payerMemberId,
            String payeeMemberId,
            String currency,
            BigDecimal amount,
            ObligationStatus status) {
        static ObligationBrief from(TradeObligation o) {
            return new ObligationBrief(
                    o.getObligationId(),
                    o.getPayerMemberId(),
                    o.getPayeeMemberId(),
                    o.getCurrency(),
                    o.getAmount(),
                    o.getStatus());
        }
    }

    public record RunDetailResponse(
            RunResponse run,
            List<ObligationBrief> obligations,
            List<PositionResponse> positions,
            BigDecimal sumNetAmount) {
    }
}
