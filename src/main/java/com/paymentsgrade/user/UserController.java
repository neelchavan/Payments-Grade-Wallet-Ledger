package com.paymentsgrade.user;

import static com.paymentsgrade.idempotency.IdempotencyService.KEY_HEADER;

import com.paymentsgrade.idempotency.IdempotencyService;
import com.paymentsgrade.idempotency.IdempotencyService.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {

    public record CreateUserRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "\\+?[0-9]{8,15}", message = "must be 8-15 digits, optional leading +") String phone) {
    }

    private final UserRepository users;
    private final IdempotencyService idempotency;

    public UserController(UserRepository users, IdempotencyService idempotency) {
        this.users = users;
        this.idempotency = idempotency;
    }

    @PostMapping("/users")
    public ResponseEntity<String> create(@RequestHeader(value = KEY_HEADER, required = false) String key,
            @Valid @RequestBody CreateUserRequest req) {
        return idempotency.execute(null, key, "POST /users", req,
                () -> Result.created(users.insert(req.name().trim(), req.email().trim(), req.phone())));
    }
}
