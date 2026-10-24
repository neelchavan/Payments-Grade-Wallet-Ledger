package com.paymentsgrade.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

@Configuration
public class LlmConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(name = "app.llm.provider", havingValue = "mock", matchIfMissing = true)
    public LlmClient mockLlmClient(ObjectMapper mapper) {
        return new MockLlmClient(mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "app.llm.provider", havingValue = "openrouter")
    public LlmClient openRouterLlmClient(@Value("${app.llm.openrouter.api-key}") String apiKey,
            @Value("${app.llm.openrouter.model}") String model,
            @Value("${app.llm.openrouter.base-url}") String baseUrl) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("OPENROUTER_API_KEY must be set when LLM_PROVIDER=openrouter");
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        RestClient http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey).build();
        return new OpenRouterLlmClient(http, model);
    }
}
