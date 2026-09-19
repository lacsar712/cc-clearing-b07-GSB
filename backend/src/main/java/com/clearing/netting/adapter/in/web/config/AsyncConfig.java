package com.clearing.netting.adapter.in.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Dedicated bounded executor for netting run processing. Runs stay small and
 * netting-scoped — this is intentionally not a generic task framework.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "nettingRunTaskExecutor")
    public Executor nettingRunTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("netting-run-");
        executor.initialize();
        return executor;
    }
}
