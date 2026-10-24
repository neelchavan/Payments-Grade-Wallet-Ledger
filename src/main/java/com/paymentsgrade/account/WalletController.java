package com.paymentsgrade.account;

import static com.paymentsgrade.idempotency.IdempotencyService.KEY_HEADER;

import com.paymentsgrade.account.AccountRepository.Wallet;
import com.paymentsgrade.account.LedgerRepository.StatementLine;
import com.paymentsgrade.idempotency.IdempotencyService;
import com.paymentsgrade.idempotency.IdempotencyService.Result;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WalletController {

    public record Statement(List<StatementLine> items, int limit, int offset) {
    }

    private final WalletService wallets;
    private final IdempotencyService idempotency;

    public WalletController(WalletService wallets, IdempotencyService idempotency) {
        this.wallets = wallets;
        this.idempotency = idempotency;
    }

    @PostMapping("/users/{userId}/wallets")
    public ResponseEntity<String> create(@PathVariable long userId, @RequestHeader("X-User-Id") long caller,
            @RequestHeader(value = KEY_HEADER, required = false) String key) {
        return idempotency.execute(caller, key, "POST /users/" + userId + "/wallets", null,
                () -> Result.created(wallets.create(caller, userId)));
    }

    @GetMapping("/users/{userId}/wallets")
    public List<Wallet> list(@PathVariable long userId, @RequestHeader("X-User-Id") long caller) {
        return wallets.list(caller, userId);
    }

    @GetMapping("/wallets/{walletId}/balance")
    public Wallet balance(@PathVariable long walletId, @RequestHeader("X-User-Id") long caller) {
        return wallets.balance(caller, walletId);
    }

    @GetMapping("/wallets/{walletId}/statement")
    public Statement statement(@PathVariable long walletId, @RequestHeader("X-User-Id") long caller,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset) {
        return new Statement(wallets.statement(caller, walletId, limit, offset), limit, offset);
    }
}
