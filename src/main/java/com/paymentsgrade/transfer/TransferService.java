package com.paymentsgrade.transfer;

import com.paymentsgrade.account.AccountRepository;
import com.paymentsgrade.account.AccountRepository.Locked;
import com.paymentsgrade.account.LedgerRepository;
import com.paymentsgrade.risk.RiskService;
import com.paymentsgrade.risk.RiskService.Hit;
import com.paymentsgrade.web.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private static final String CASH_IN = "CASH_IN";
    private static final String CASH_OUT = "CASH_OUT";

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerRepository ledger;
    private final RiskService risk;

    public TransferService(AccountRepository accounts, TransferRepository transfers, LedgerRepository ledger,
            RiskService risk) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.ledger = ledger;
        this.risk = risk;
    }

    @Transactional
    public Transfer topup(long userId, long walletId, BigDecimal amount, String note) {
        long cashIn = accounts.systemAccountId(CASH_IN);
        Map<Long, Locked> locked = lock(userId, cashIn, walletId);
        requireOwnedWallet(locked, walletId, userId);
        return post(TransferType.TOPUP, locked, cashIn, walletId, amount, note, null);
    }

    @Transactional
    public Transfer withdraw(long userId, long walletId, BigDecimal amount, String note) {
        long cashOut = accounts.systemAccountId(CASH_OUT);
        Map<Long, Locked> locked = lock(userId, walletId, cashOut);
        requireOwnedWallet(locked, walletId, userId);
        return post(TransferType.WITHDRAWAL, locked, walletId, cashOut, amount, note, null);
    }

    @Transactional
    public Transfer peerTransfer(long userId, long fromWalletId, long toWalletId, BigDecimal amount, String note) {
        if (fromWalletId == toWalletId) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SAME_WALLET", "Cannot transfer to the same wallet");
        }
        Map<Long, Locked> locked = lock(userId, fromWalletId, toWalletId);
        requireOwnedWallet(locked, fromWalletId, userId);
        Locked to = locked.get(toWalletId);
        if (to == null || !to.isWallet()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RECIPIENT_NOT_FOUND", "Recipient wallet not found");
        }
        return post(TransferType.PEER_TRANSFER, locked, fromWalletId, toWalletId, amount, note, null);
    }

    // Full reversal only; the original becomes REVERSED.
    @Transactional
    public Transfer reverse(long transferId) {
        transfers.setActor("admin");
        Transfer original = transfers.lockById(transferId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer not found"));
        if (original.transferType() == TransferType.REVERSAL || original.status() != TransferStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_REVERSIBLE", "Only completed, non-reversal transfers can be reversed");
        }
        Map<Long, Locked> locked = accounts.lockForUpdate(List.of(original.fromAccountId(), original.toAccountId()));
        Transfer reversal = post(TransferType.REVERSAL, locked, original.toAccountId(), original.fromAccountId(),
                original.amount(), "Reversal of transfer " + original.id(), original.id());
        transfers.updateStatus(original.id(), TransferStatus.REVERSED);
        return reversal;
    }

    // A held transfer has no ledger entries and no reservation, so the balance is checked again on approval.
    @Transactional
    public Transfer decide(long transferId, boolean approve) {
        transfers.setActor("admin");
        Transfer held = transfers.lockById(transferId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer not found"));
        if (held.status() != TransferStatus.HELD) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_HELD", "Transfer is not waiting for review");
        }
        if (approve) {
            Map<Long, Locked> locked = accounts.lockForUpdate(List.of(held.fromAccountId(), held.toAccountId()));
            requireFunds(locked.get(held.fromAccountId()), held.amount());
            ledger.insertPair(held.id(), held.fromAccountId(), held.toAccountId(), held.amount());
            accounts.adjustBalance(held.fromAccountId(), held.amount().negate());
            accounts.adjustBalance(held.toAccountId(), held.amount());
        }
        transfers.updateStatus(held.id(), approve ? TransferStatus.COMPLETED : TransferStatus.REJECTED);
        risk.recordDecision(held.id(), approve ? "APPROVED" : "REJECTED");
        return transfers.findById(held.id());
    }

    @Transactional(readOnly = true)
    public Transfer get(long userId, long transferId) {
        return transfers.findVisibleTo(transferId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer not found"));
    }

    private Map<Long, Locked> lock(long userId, long accountA, long accountB) {
        transfers.setActor("user:" + userId);
        return accounts.lockForUpdate(List.of(accountA, accountB));
    }

    private static void requireOwnedWallet(Map<Long, Locked> locked, long walletId, long userId) {
        Locked wallet = locked.get(walletId);
        if (wallet == null || !wallet.isWallet() || wallet.userId() == null || wallet.userId() != userId) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet not found");
        }
    }

    // Callers hold FOR UPDATE locks on both accounts, so the balance read here cannot go stale.
    private Transfer post(TransferType type, Map<Long, Locked> locked, long fromId, long toId, BigDecimal amount,
            String note, Long reverses) {
        Locked from = locked.get(fromId);
        requireFunds(from, amount);
        List<Hit> hits = RiskService.applies(type) ? risk.evaluate(fromId, toId, type, amount) : List.of();
        if (hits.stream().anyMatch(Hit::isHold)) {
            Transfer held = transfers.insert(type, TransferStatus.HELD, fromId, toId, amount, note, reverses);
            risk.recordHits(held.id(), hits);
            return held;
        }
        Transfer transfer = transfers.insert(type, TransferStatus.COMPLETED, fromId, toId, amount, note, reverses);
        ledger.insertPair(transfer.id(), fromId, toId, amount);
        accounts.adjustBalance(fromId, amount.negate());
        accounts.adjustBalance(toId, amount);
        risk.recordHits(transfer.id(), hits);
        return transfer;
    }

    private static void requireFunds(Locked from, BigDecimal amount) {
        if (from.isWallet() && from.balance().compareTo(amount) < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "Insufficient funds");
        }
    }
}
