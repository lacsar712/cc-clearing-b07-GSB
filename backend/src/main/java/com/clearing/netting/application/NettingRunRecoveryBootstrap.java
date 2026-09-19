package com.clearing.netting.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * On startup, fail runs left in CREATED/RUNNING by a previous JVM. A crashed
 * worker can never finish, so an eternal "in progress" row would be a lie —
 * terminal FAILED with an explicit reason is the recoverable state.
 */
@Component
public class NettingRunRecoveryBootstrap {

    private static final Logger log = LoggerFactory.getLogger(NettingRunRecoveryBootstrap.class);

    private final NettingRunStatusService statusService;

    public NettingRunRecoveryBootstrap(NettingRunStatusService statusService) {
        this.statusService = statusService;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void recover() {
        int recovered = statusService.recoverInterruptedRuns();
        if (recovered > 0) {
            log.warn("marked {} interrupted netting run(s) as FAILED on startup", recovered);
        }
    }
}
