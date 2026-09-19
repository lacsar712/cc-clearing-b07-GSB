package com.clearing.netting.domain.model;

/**
 * Server-persisted execution phases of a netting run.
 * VALIDATING -> CALCULATING -> PERSISTING, then the run becomes COMPLETED.
 * On failure the run is FAILED and stage stays at the phase that failed.
 */
public enum NettingStage {
    VALIDATING,
    CALCULATING,
    PERSISTING
}
