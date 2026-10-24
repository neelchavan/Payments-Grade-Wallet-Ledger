package com.paymentsgrade.transfer;

import static com.paymentsgrade.idempotency.IdempotencyService.KEY_HEADER;

import com.paymentsgrade.idempotency.IdempotencyService;
import com.paymentsgrade.idempotency.IdempotencyService.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransferController {

    public record WalletMoneyRequest(
            @NotNull Long walletId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @Size(max = 200) String note) {
    }

    public record PeerTransferRequest(
            @NotNull Long fromWalletId,
            @NotNull Long toWalletId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @Size(max = 200) String note) {
    }

    private final TransferService service;
    private final IdempotencyService idempotency;

    public TransferController(TransferService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @PostMapping("/transfers/topup")
    public ResponseEntity<String> topup(@RequestHeader("X-User-Id") long caller,
            @RequestHeader(value = KEY_HEADER, required = false) String key,
            @Valid @RequestBody WalletMoneyRequest req) {
        return idempotency.execute(caller, key, "POST /transfers/topup", req,
                () -> result(service.topup(caller, req.walletId(), req.amount(), req.note())));
    }

    @PostMapping("/transfers/withdrawal")
    public ResponseEntity<String> withdraw(@RequestHeader("X-User-Id") long caller,
            @RequestHeader(value = KEY_HEADER, required = false) String key,
            @Valid @RequestBody WalletMoneyRequest req) {
        return idempotency.execute(caller, key, "POST /transfers/withdrawal", req,
                () -> result(service.withdraw(caller, req.walletId(), req.amount(), req.note())));
    }

    @PostMapping("/transfers/peer")
    public ResponseEntity<String> peer(@RequestHeader("X-User-Id") long caller,
            @RequestHeader(value = KEY_HEADER, required = false) String key,
            @Valid @RequestBody PeerTransferRequest req) {
        return idempotency.execute(caller, key, "POST /transfers/peer", req,
                () -> result(service.peerTransfer(caller, req.fromWalletId(), req.toWalletId(),
                        req.amount(), req.note())));
    }

    // A transfer held for review is accepted but not yet done.
    private static Result result(Transfer transfer) {
        return new Result(transfer.status() == TransferStatus.HELD ? HttpStatus.ACCEPTED : HttpStatus.CREATED, transfer);
    }

    @GetMapping("/transfers/{id}")
    public Transfer get(@PathVariable long id, @RequestHeader("X-User-Id") long caller) {
        return service.get(caller, id);
    }

    @PostMapping("/admin/transfers/{id}/reverse")
    public ResponseEntity<String> reverse(@PathVariable long id,
            @RequestHeader(value = KEY_HEADER, required = false) String key) {
        return idempotency.execute(null, key, "POST /admin/transfers/" + id + "/reverse", null,
                () -> Result.created(service.reverse(id)));
    }
}
