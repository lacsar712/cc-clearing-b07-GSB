package com.clearing.netting.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public class NettingRun {
    private final String runId;
    private final LocalDate settleDate;
    private final String currency;
    private NettingRunStatus status;
    private final Instant createdAt;
    private String failureReason;
    private NettingStage stage;
    private Instant stageUpdatedAt;

    public NettingRun(
            String runId,
            LocalDate settleDate,
            String currency,
            NettingRunStatus status,
            Instant createdAt,
            String failureReason,
            NettingStage stage,
            Instant stageUpdatedAt) {
        this.runId = Objects.requireNonNull(runId);
        this.settleDate = Objects.requireNonNull(settleDate);
        this.currency = Objects.requireNonNull(currency).toUpperCase();
        this.status = Objects.requireNonNull(status);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.failureReason = failureReason;
        this.stage = stage;
        this.stageUpdatedAt = stageUpdatedAt;
    }

    public static NettingRun create(LocalDate settleDate, String currency) {
        return new NettingRun(
                UUID.randomUUID().toString(),
                settleDate,
                currency,
                NettingRunStatus.CREATED,
                Instant.now(),
                null,
                null,
                null);
    }

    public void markRunning() {
        this.status = NettingRunStatus.RUNNING;
        this.stage = NettingStage.VALIDATING;
        this.stageUpdatedAt = Instant.now();
        this.failureReason = null;
    }

    public void updateStage(NettingStage stage) {
        if (this.status != NettingRunStatus.RUNNING) {
            throw new IllegalStateException("can only update stage while RUNNING, current=" + this.status);
        }
        this.stage = Objects.requireNonNull(stage);
        this.stageUpdatedAt = Instant.now();
    }

    public void markCompleted() {
        this.status = NettingRunStatus.COMPLETED;
        this.failureReason = null;
        this.stage = null;
        this.stageUpdatedAt = null;
    }

    public void markFailed(String reason) {
        this.status = NettingRunStatus.FAILED;
        this.failureReason = reason;
        this.stage = null;
        this.stageUpdatedAt = null;
    }

    public String getRunId() {
        return runId;
    }

    public LocalDate getSettleDate() {
        return settleDate;
    }

    public String getCurrency() {
        return currency;
    }

    public NettingRunStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public NettingStage getStage() {
        return stage;
    }

    public Instant getStageUpdatedAt() {
        return stageUpdatedAt;
    }
}
