package com.clearing.netting.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Drives netting execution off the request thread so the run row (and each
 * stage transition) is visible in the database while work is ongoing.
 */
@Component
public class NettingRunExecutor {

    private static final Logger log = LoggerFactory.getLogger(NettingRunExecutor.class);

    private final NettingApplicationService nettingApplicationService;

    public NettingRunExecutor(NettingApplicationService nettingApplicationService) {
        this.nettingApplicationService = nettingApplicationService;
    }

    @Async("nettingRunTaskExecutor")
    @EventListener
    public void onExecutionRequested(NettingRunExecutionEvent event) {
        try {
            nettingApplicationService.processRun(event);
        } catch (RuntimeException ex) {
            // processRun already marks the run FAILED for business/system errors;
            // nothing here should ever escape the worker thread.
            log.error("unhandled failure in netting run worker {}", event.runId(), ex);
        }
    }
}
