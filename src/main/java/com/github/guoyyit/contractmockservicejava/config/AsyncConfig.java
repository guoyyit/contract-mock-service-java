package com.github.guoyyit.contractmockservicejava.config;

import com.github.guoyyit.contractmockservicejava.service.DelayTaskRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Provides the scheduler used for scenario delays. */
@Configuration
public class AsyncConfig {

    @Bean
    public ThreadPoolTaskScheduler mockScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(8);
        scheduler.setThreadNamePrefix("mock-delay-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }
}
