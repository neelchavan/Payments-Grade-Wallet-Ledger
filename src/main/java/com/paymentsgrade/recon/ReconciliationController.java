package com.paymentsgrade.recon;

import static com.paymentsgrade.idempotency.IdempotencyService.KEY_HEADER;

import com.paymentsgrade.idempotency.IdempotencyService;
import com.paymentsgrade.idempotency.IdempotencyService.Result;
import com.paymentsgrade.recon.ReconciliationService.RunView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReconciliationController {

    private final ReconciliationService service;
    private final IdempotencyService idempotency;

    public ReconciliationController(ReconciliationService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @PostMapping("/admin/reconciliation/run")
    public ResponseEntity<String> run(@RequestHeader(value = KEY_HEADER, required = false) String key) {
        return idempotency.execute(null, key, "POST /admin/reconciliation/run", null,
                () -> new Result(HttpStatus.ACCEPTED, service.startAsync("MANUAL")));
    }

    @GetMapping("/admin/reconciliation/runs/{id}")
    public RunView get(@PathVariable long id) {
        return service.get(id);
    }
}
