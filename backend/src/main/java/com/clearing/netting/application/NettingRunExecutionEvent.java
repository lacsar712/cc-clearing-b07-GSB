package com.clearing.netting.application;

import java.time.LocalDate;

/**
 * Published after a run row has been committed as RUNNING; the async listener
 * performs the actual VALIDATING -> CALCULATING -> PERSISTING work.
 */
public record NettingRunExecutionEvent(String runId, LocalDate settleDate, String currency) {
}
