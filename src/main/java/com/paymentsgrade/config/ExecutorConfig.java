package com.paymentsgrade.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExecutorConfig {

    // One thread: manual runs queue up, and the job lock stops overlap across processes.
    @Bean(destroyMethod = "shutdown")
    public ExecutorService reconciliationExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "reconciliation");
            thread.setDaemon(true);
            return thread;
        });
    }
}
