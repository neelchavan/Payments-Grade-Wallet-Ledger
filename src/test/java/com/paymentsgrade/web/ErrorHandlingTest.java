package com.paymentsgrade.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = ErrorHandlingTest.Probe.class)
@Import({RequestIdFilter.class, GlobalExceptionHandler.class, ErrorHandlingTest.Probe.class})
class ErrorHandlingTest {

    private static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @RestController
    static class Probe {
        record Body(@Positive long amount) {
        }

        @GetMapping("/probe/ok")
        String ok() {
            return "ok";
        }

        @GetMapping("/probe/api")
        String api() {
            throw new ApiException(HttpStatus.CONFLICT, "BUSY", "Resource busy");
        }

        @GetMapping("/probe/boom")
        String boom() {
            throw new IllegalStateException("secret internal detail");
        }

        @GetMapping("/probe/header")
        String header(@RequestHeader("X-User-Id") long userId) {
            return "ok";
        }

        @PostMapping("/probe/body")
        String body(@Valid @RequestBody Body body) {
            return "ok";
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void generatesRequestIdWhenAbsent() throws Exception {
        mvc.perform(get("/probe/ok"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", matchesPattern(UUID_RE)));
    }

    @Test
    void echoesSafeClientRequestId() throws Exception {
        mvc.perform(get("/probe/ok").header("X-Request-Id", "abc-123"))
                .andExpect(header().string("X-Request-Id", "abc-123"));
    }

    @Test
    void replacesUnsafeClientRequestId() throws Exception {
        mvc.perform(get("/probe/ok").header("X-Request-Id", "bad id\twith spaces"))
                .andExpect(header().string("X-Request-Id", matchesPattern(UUID_RE)));
    }

    @Test
    void apiExceptionUsesStandardShape() throws Exception {
        mvc.perform(get("/probe/api").header("X-Request-Id", "req-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSY"))
                .andExpect(jsonPath("$.message").value("Resource busy"))
                .andExpect(jsonPath("$.request_id").value("req-1"));
    }

    @Test
    void unexpectedErrorHidesDetails() throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error"))
                .andExpect(jsonPath("$.request_id").exists());
    }

    @Test
    void missingHeaderIs400() throws Exception {
        mvc.perform(get("/probe/header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("X-User-Id")))
                .andExpect(jsonPath("$.request_id").exists());
    }

    @Test
    void validationFailureIs400() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("amount")));
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void unknownPathIs404WithStandardShape() throws Exception {
        mvc.perform(get("/does/not/exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.request_id").exists());
    }
}
