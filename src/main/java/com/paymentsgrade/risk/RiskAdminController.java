package com.paymentsgrade.risk;

import static com.paymentsgrade.idempotency.IdempotencyService.KEY_HEADER;

import com.paymentsgrade.idempotency.IdempotencyService;
import com.paymentsgrade.idempotency.IdempotencyService.Result;
import com.paymentsgrade.risk.RiskService.Flag;
import com.paymentsgrade.transfer.Transfer;
import com.paymentsgrade.transfer.TransferRepository;
import com.paymentsgrade.transfer.TransferService;
import com.paymentsgrade.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RiskAdminController {

    public enum Decision {
        APPROVE, REJECT
    }

    public record DecisionRequest(@NotNull Decision decision) {
    }

    public record QueueItem(Transfer transfer, List<Flag> flags) {
    }

    public record Queue(List<QueueItem> items, int limit, int offset) {
    }

    private static final int MAX_LIMIT = 100;

    private final TransferRepository transfers;
    private final TransferService transferService;
    private final RiskService risk;
    private final IdempotencyService idempotency;

    public RiskAdminController(TransferRepository transfers, TransferService transferService, RiskService risk,
            IdempotencyService idempotency) {
        this.transfers = transfers;
        this.transferService = transferService;
        this.risk = risk;
        this.idempotency = idempotency;
    }

    @GetMapping("/admin/risk/queue")
    public Queue queue(@RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
        if (limit < 1 || limit > MAX_LIMIT || offset < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "limit must be 1-" + MAX_LIMIT + " and offset >= 0");
        }
        List<Transfer> held = transfers.findHeld(limit, offset);
        Map<Long, List<Flag>> flags = risk.flagsFor(held.stream().map(Transfer::id).toList()).stream()
                .collect(Collectors.groupingBy(Flag::transferId));
        List<QueueItem> items = held.stream().map(t -> new QueueItem(t, flags.getOrDefault(t.id(), List.of()))).toList();
        return new Queue(items, limit, offset);
    }

    @PostMapping("/admin/risk/{transferId}/decision")
    public ResponseEntity<String> decide(@PathVariable long transferId,
            @RequestHeader(value = KEY_HEADER, required = false) String key,
            @Valid @RequestBody DecisionRequest req) {
        return idempotency.execute(null, key, "POST /admin/risk/" + transferId + "/decision", req,
                () -> new Result(HttpStatus.OK, transferService.decide(transferId, req.decision() == Decision.APPROVE)));
    }
}
