package com.paymentsgrade.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenRouterLlmClientTest {

    private static final String SECRET = "sk-or-test-secret";

    private MockRestServiceServer server;
    private OpenRouterLlmClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://openrouter.test/api/v1")
                .defaultHeader("Authorization", "Bearer " + SECRET);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenRouterLlmClient(builder.build(), "test/model");
    }

    @Test
    void sendsAnOpenAiStyleChatRequestAndReturnsTheReplyText() {
        server.expect(requestTo("https://openrouter.test/api/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + SECRET))
                .andExpect(jsonPath("$.model").value("test/model"))
                .andExpect(jsonPath("$.temperature").value(0))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("be strict"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("hello"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"type\\\":1}\"}}]}",
                        MediaType.APPLICATION_JSON));

        String reply = client.complete(new LlmRequest(LlmRequest.Task.PARSE_QUESTION, "be strict", "hello"));

        assertThat(reply).isEqualTo("{\"type\":1}");
        server.verify();
    }

    @Test
    void failuresBecomeLlmExceptionWithoutLeakingTheKeyOrTheBody() {
        server.expect(requestTo("https://openrouter.test/api/v1/chat/completions"))
                .andRespond(withServerError().body("upstream said: Bearer " + SECRET));

        assertThatThrownBy(() -> client.complete(new LlmRequest(LlmRequest.Task.PARSE_QUESTION, "s", "u")))
                .isInstanceOf(LlmException.class)
                .hasNoCause()
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET).doesNotContain("upstream"));
    }

    @Test
    void anEmptyReplyIsAnError() {
        server.expect(requestTo("https://openrouter.test/api/v1/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.complete(new LlmRequest(LlmRequest.Task.PARSE_QUESTION, "s", "u")))
                .isInstanceOf(LlmException.class);
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LlmConfig.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void theMockIsTheDefaultProvider() {
        runner.run(ctx -> assertThat(ctx.getBean(LlmClient.class)).isInstanceOf(MockLlmClient.class));
    }

    @Test
    void openRouterIsUsedWhenConfiguredWithAKey() {
        runner.withPropertyValues("app.llm.provider=openrouter", "app.llm.openrouter.api-key=" + SECRET,
                        "app.llm.openrouter.model=m", "app.llm.openrouter.base-url=https://openrouter.test/api/v1")
                .run(ctx -> assertThat(ctx.getBean(LlmClient.class)).isInstanceOf(OpenRouterLlmClient.class));
    }

    @Test
    void openRouterWithoutAKeyFailsAtStartup() {
        runner.withPropertyValues("app.llm.provider=openrouter", "app.llm.openrouter.api-key=",
                        "app.llm.openrouter.model=m", "app.llm.openrouter.base-url=https://openrouter.test/api/v1")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
