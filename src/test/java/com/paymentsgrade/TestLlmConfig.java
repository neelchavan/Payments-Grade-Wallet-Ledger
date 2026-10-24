package com.paymentsgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsgrade.llm.MockLlmClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// Fixes "today" at 2026-10-18 so relative questions like "last month" have stable answers.
@TestConfiguration
public class TestLlmConfig {

    @Bean
    @Primary
    public ScriptedLlmClient scriptedLlm(ObjectMapper mapper) {
        return new ScriptedLlmClient(new MockLlmClient(mapper));
    }

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"), ZoneOffset.UTC);
    }
}
