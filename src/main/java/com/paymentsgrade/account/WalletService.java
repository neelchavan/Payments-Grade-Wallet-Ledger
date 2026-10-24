package com.paymentsgrade.account;

import com.paymentsgrade.account.AccountRepository.Wallet;
import com.paymentsgrade.account.LedgerRepository.StatementLine;
import com.paymentsgrade.web.ApiException;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class WalletService {

    static final int MAX_LIMIT = 100;

    private final AccountRepository accounts;
    private final LedgerRepository ledger;

    public WalletService(AccountRepository accounts, LedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    public Wallet create(long callerId, long userId) {
        requireSelf(callerId, userId);
        try {
            return accounts.createWallet(userId);
        } catch (DataIntegrityViolationException e) {
            throw userNotFound();
        }
    }

    public List<Wallet> list(long callerId, long userId) {
        requireSelf(callerId, userId);
        return accounts.walletsOf(userId);
    }

    public Wallet balance(long callerId, long walletId) {
        return accounts.findOwnedWallet(walletId, callerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet not found"));
    }

    public List<StatementLine> statement(long callerId, long walletId, int limit, int offset) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "offset must not be negative");
        }
        balance(callerId, walletId);
        return ledger.statement(walletId, limit, offset);
    }

    // Other users' resources are reported as missing, never as forbidden.
    private static void requireSelf(long callerId, long userId) {
        if (callerId != userId) {
            throw userNotFound();
        }
    }

    private static ApiException userNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found");
    }
}
