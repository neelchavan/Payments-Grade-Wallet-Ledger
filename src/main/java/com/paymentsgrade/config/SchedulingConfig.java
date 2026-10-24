package com.paymentsgrade.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

// Only the worker container runs @Scheduled jobs.
@Configuration
@Profile("worker")
@EnableScheduling
public class SchedulingConfig {
}
