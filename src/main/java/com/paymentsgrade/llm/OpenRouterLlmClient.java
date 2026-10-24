package com.paymentsgrade.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Calls OpenRouter's OpenAI-compatible chat completions API. */
public class OpenRouterLlmClient implements LlmClient {

    private final RestClient http;
    private final String model;

    public OpenRouterLlmClient(RestClient http, String model) {
        this.http = http;
        this.model = model;
    }

    @Override
    public String complete(LlmRequest request) {
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0,
                "max_tokens", 300,
                "messages", List.of(
                        Map.of("role", "system", "content", request.system()),
                        Map.of("role", "user", "content", request.user())));
        JsonNode response;
        try {
            response = http.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            // The cause is dropped on purpose: it can echo request details.
            throw new LlmException("OpenRouter request failed: " + e.getClass().getSimpleName());
        }
        String text = response == null ? null : response.path("choices").path(0).path("message").path("content").asText(null);
        if (text == null || text.isBlank()) {
            throw new LlmException("OpenRouter returned no content");
        }
        return text;
    }
}
