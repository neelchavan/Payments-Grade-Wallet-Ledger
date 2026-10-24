package com.paymentsgrade.qa;

import com.paymentsgrade.qa.StatementQaService.Response;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatementQaController {

    public record AskRequest(@NotBlank String question) {
    }

    private final StatementQaService service;

    public StatementQaController(StatementQaService service) {
        this.service = service;
    }

    // Read-only, so it does not take an Idempotency-Key.
    @PostMapping("/users/{userId}/statement/ask")
    public Response ask(@PathVariable long userId, @RequestHeader("X-User-Id") long caller,
            @Valid @RequestBody AskRequest req) {
        return service.ask(caller, userId, req.question());
    }
}
