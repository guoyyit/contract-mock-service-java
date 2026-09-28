package com.github.guoyyit.contractmockservicejava.engine;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Dedicated scheduler for scenario delays. Tasks are released when a
 * request is cancelled or completes.
 */
@Configuration
public class SchedulerConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService mockDelayScheduler() {
        return Executors.newScheduledThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                runnable -> {
                    Thread thread = new Thread(runnable, "mock-delay");
                    thread.setDaemon(true);
                    return thread;
                });
    }
}
