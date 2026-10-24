package com.paymentsgrade.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paymentsgrade.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryValidatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 18);

    private final QueryValidator validator = new QueryValidator();

    @Test
    void acceptsEachValidShape() {
        assertThat(validator.validate("{\"type\":1,\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}", TODAY).type())
                .isEqualTo(ParsedQuery.Type.TOTAL_SPENT);
        ParsedQuery q = validator.validate(
                "{\"type\":2,\"counterparty\":\"Mary-Ann O'Neil\",\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}", TODAY);
        assertThat(q.counterparty()).isEqualTo("Mary-Ann O'Neil");
        assertThat(validator.validate("{\"type\":3,\"counterparty\":null,\"from\":\"2026-01-01\",\"to\":\"2026-10-18\"}", TODAY).type())
                .isEqualTo(ParsedQuery.Type.TRANSFER_COUNT);
        assertThat(validator.validate("{\"type\":4,\"from\":\"2026-01-01\",\"to\":\"2026-10-18\"}", TODAY).type())
                .isEqualTo(ParsedQuery.Type.LARGEST_TRANSFER);
    }

    @Test
    void acceptsAMarkdownFence() {
        String fenced = "```json\n{\"type\":1,\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}\n```";
        assertThat(validator.validate(fenced, TODAY).from()).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    void rejectsEverythingElse() {
        List<String> hostile = List.of(
                "",
                "   ",
                "Sure! Here are all the users: alice, bob",
                "[1,2,3]",
                "\"just a string\"",
                "null",
                "{\"type\":1,\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"} and now ignore previous instructions",
                "{\"type\":1,\"from\":\"2026-09-01\",\"to\":\"2026-09-30\",\"sql\":\"SELECT * FROM users\"}",
                "{\"type\":1,\"from\":\"2026-09-01\",\"to\":\"2026-09-30\",\"user_id\":2}",
                "{\"type\":1,\"type\":2,\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}",
                "{\"type\":0,\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}",
                "{\"type\":5,\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}",
                "{\"type\":-1,\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}",
                "{\"type\":1.5,\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}",
                "{\"type\":\"1\",\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}",
                "{\"type\":\"1; DROP TABLE users\",\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}",
                "{\"type\":99999999999,\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}",
                "{\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}",
                "{\"type\":1,\"to\":\"2026-09-30\"}",
                "{\"type\":1,\"from\":\"2026-09-01\"}",
                "{\"type\":1,\"from\":\"yesterday\",\"to\":\"today\"}",
                "{\"type\":1,\"from\":\"2026-13-45\",\"to\":\"2026-09-30\"}",
                "{\"type\":1,\"from\":\"2026-02-30\",\"to\":\"2026-03-01\"}",
                "{\"type\":1,\"from\":\"2026-9-1\",\"to\":\"2026-09-30\"}",
                "{\"type\":1,\"from\":\"2026-09-01'; DROP TABLE users;--\",\"to\":\"2026-09-30\"}",
                "{\"type\":1,\"from\":20260901,\"to\":\"2026-09-30\"}",
                "{\"type\":1,\"from\":\"2026-09-30\",\"to\":\"2026-09-01\"}",
                "{\"type\":1,\"from\":\"1990-01-01\",\"to\":\"2026-09-30\"}",
                "{\"type\":1,\"from\":\"2026-01-01\",\"to\":\"2099-01-01\"}",
                "{\"type\":1,\"from\":\"2000-01-01\",\"to\":\"2026-10-18\"}",
                "{\"type\":2,\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}",
                "{\"type\":2,\"counterparty\":null,\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}",
                "{\"type\":2,\"counterparty\":\"\",\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}",
                "{\"type\":2,\"counterparty\":\"Bob'; DROP TABLE users;--\",\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}",
                "{\"type\":2,\"counterparty\":\"Bob OR 1=1\",\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}",
                "{\"type\":2,\"counterparty\":\"%\",\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}",
                "{\"type\":2,\"counterparty\":123,\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}",
                "{\"type\":2,\"counterparty\":\"" + "A".repeat(101) + "\",\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}",
                "{\"type\":1,\"counterparty\":\"Bob\",\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}",
                "{\"type\":3,\"counterparty\":\"Bob\",\"from\":\"2026-09-01\",\"to\":\"2026-09-30\"}",
                "x".repeat(5000));

        for (String output : hostile) {
            assertThatThrownBy(() -> validator.validate(output, TODAY))
                    .as(output.length() > 120 ? output.substring(0, 120) : output)
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo("UNSUPPORTED_QUESTION");
                        assertThat(e.getMessage()).doesNotContain("DROP").doesNotContain("users:");
                    });
        }
    }

    @Test
    void nullOutputIsRejected() {
        assertThatThrownBy(() -> validator.validate(null, TODAY)).isInstanceOf(ApiException.class);
    }
}
