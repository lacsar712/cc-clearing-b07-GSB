package com.clearing.netting.domain.model;

/**
 * Server-persisted execution stages of a netting run.
 * VALIDATING -> CALCULATING -> PERSISTING, observable while status = RUNNING.
 */
public enum NettingStage {
    VALIDATING,
    CALCULATING,
    PERSISTING
}
