package com.paymentsgrade.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paymentsgrade.TransferTestSupport;
import com.paymentsgrade.web.ApiException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TransferServiceTest extends TransferTestSupport {

    @Autowired
    TransferService service;

    @Test
    void topupCreditsWalletAndDebitsCashIn() {
        Party alice = newParty();
        long cashIn = accounts.systemAccountId("CASH_IN");
        BigDecimal cashInBefore = storedBalance(cashIn);

        Transfer t = service.topup(alice.userId(), alice.walletId(), money("250.50"), "salary");

        assertThat(t.transferType()).isEqualTo(TransferType.TOPUP);
        assertThat(t.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(storedBalance(alice.walletId())).isEqualByComparingTo("250.50");
        assertThat(storedBalance(cashIn)).isEqualByComparingTo(cashInBefore.subtract(money("250.50")));
        assertThat(ledgerBalance(alice.walletId())).isEqualByComparingTo("250.50");
    }

    @Test
    void peerTransferMovesMoneyAndWritesTwoBalancedEntries() {
        Party alice = newParty();
        Party bob = newParty();
        service.topup(alice.userId(), alice.walletId(), money("100"), null);

        Transfer t = service.peerTransfer(alice.userId(), alice.walletId(), bob.walletId(), money("30.25"), "lunch");

        assertThat(storedBalance(alice.walletId())).isEqualByComparingTo("69.75");
        assertThat(storedBalance(bob.walletId())).isEqualByComparingTo("30.25");
        List<String> entries = jdbc.sql("SELECT direction || ':' || account_id FROM ledger_entries WHERE transfer_id = :t ORDER BY id")
                .param("t", t.id()).query(String.class).list();
        assertThat(entries).containsExactly("DEBIT:" + alice.walletId(), "CREDIT:" + bob.walletId());
    }

    @Test
    void withdrawalMovesMoneyToCashOut() {
        Party alice = newParty();
        service.topup(alice.userId(), alice.walletId(), money("100"), null);

        service.withdraw(alice.userId(), alice.walletId(), money("40"), null);

        assertThat(storedBalance(alice.walletId())).isEqualByComparingTo("60");
        assertThat(ledgerBalance(alice.walletId())).isEqualByComparingTo("60");
    }

    @Test
    void insufficientFundsLeavesNoTrace() {
        Party alice = newParty();
        Party bob = newParty();
        service.topup(alice.userId(), alice.walletId(), money("10"), null);
        long transfersBefore = count("transfers");
        long entriesBefore = count("ledger_entries");
        long historyBefore = count("transfer_status_history");

        assertThatThrownBy(() -> service.peerTransfer(alice.userId(), alice.walletId(), bob.walletId(), money("10.01"), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INSUFFICIENT_FUNDS"));
        assertThatThrownBy(() -> service.withdraw(alice.userId(), alice.walletId(), money("11"), null))
                .isInstanceOf(ApiException.class);

        assertThat(count("transfers")).isEqualTo(transfersBefore);
        assertThat(count("ledger_entries")).isEqualTo(entriesBefore);
        assertThat(count("transfer_status_history")).isEqualTo(historyBefore);
        assertThat(storedBalance(alice.walletId())).isEqualByComparingTo("10");
    }

    @Test
    void cannotMoveMoneyOutOfSomeoneElsesWallet() {
        Party alice = newParty();
        Party bob = newParty();
        service.topup(alice.userId(), alice.walletId(), money("50"), null);

        assertThatThrownBy(() -> service.peerTransfer(bob.userId(), alice.walletId(), bob.walletId(), money("5"), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("WALLET_NOT_FOUND"));
        assertThatThrownBy(() -> service.topup(bob.userId(), alice.walletId(), money("5"), null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.withdraw(bob.userId(), alice.walletId(), money("5"), null))
                .isInstanceOf(ApiException.class);
        assertThat(storedBalance(alice.walletId())).isEqualByComparingTo("50");
    }

    @Test
    void recipientMustBeAnExistingWallet() {
        Party alice = newParty();
        service.topup(alice.userId(), alice.walletId(), money("50"), null);
        long cashIn = accounts.systemAccountId("CASH_IN");

        assertThatThrownBy(() -> service.peerTransfer(alice.userId(), alice.walletId(), cashIn, money("5"), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("RECIPIENT_NOT_FOUND"));
        assertThatThrownBy(() -> service.peerTransfer(alice.userId(), alice.walletId(), 999_999_999L, money("5"), null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.peerTransfer(alice.userId(), alice.walletId(), alice.walletId(), money("5"), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("SAME_WALLET"));
    }

    @Test
    void reversalRestoresBalancesAndMarksOriginalReversed() {
        Party alice = newParty();
        Party bob = newParty();
        service.topup(alice.userId(), alice.walletId(), money("100"), null);
        Transfer original = service.peerTransfer(alice.userId(), alice.walletId(), bob.walletId(), money("60"), null);

        Transfer reversal = service.reverse(original.id());

        assertThat(reversal.transferType()).isEqualTo(TransferType.REVERSAL);
        assertThat(reversal.reversesTransferId()).isEqualTo(original.id());
        assertThat(storedBalance(alice.walletId())).isEqualByComparingTo("100");
        assertThat(storedBalance(bob.walletId())).isEqualByComparingTo("0");
        assertThat(ledgerBalance(alice.walletId())).isEqualByComparingTo("100");
        assertThat(service.get(alice.userId(), original.id()).status()).isEqualTo(TransferStatus.REVERSED);
        List<String> history = jdbc.sql("SELECT coalesce(old_status,'-') || '>' || new_status || '@' || actor FROM transfer_status_history WHERE transfer_id = :t ORDER BY id")
                .param("t", original.id()).query(String.class).list();
        assertThat(history).containsExactly("->COMPLETED@user:" + alice.userId(), "COMPLETED>REVERSED@admin");
    }

    @Test
    void transferCanOnlyBeReversedOnce() {
        Party alice = newParty();
        Party bob = newParty();
        service.topup(alice.userId(), alice.walletId(), money("100"), null);
        Transfer original = service.peerTransfer(alice.userId(), alice.walletId(), bob.walletId(), money("10"), null);
        Transfer reversal = service.reverse(original.id());

        assertThatThrownBy(() -> service.reverse(original.id()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NOT_REVERSIBLE"));
        assertThatThrownBy(() -> service.reverse(reversal.id()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NOT_REVERSIBLE"));
        assertThatThrownBy(() -> service.reverse(999_999_999L))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("TRANSFER_NOT_FOUND"));
    }

    @Test
    void reversalFailsIfRecipientAlreadySpentTheMoney() {
        Party alice = newParty();
        Party bob = newParty();
        service.topup(alice.userId(), alice.walletId(), money("100"), null);
        Transfer original = service.peerTransfer(alice.userId(), alice.walletId(), bob.walletId(), money("60"), null);
        service.withdraw(bob.userId(), bob.walletId(), money("50"), null);

        assertThatThrownBy(() -> service.reverse(original.id()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INSUFFICIENT_FUNDS"));
        assertThat(service.get(alice.userId(), original.id()).status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(storedBalance(bob.walletId())).isEqualByComparingTo("10");
    }

    @Test
    void transfersAreOnlyVisibleToTheirParticipants() {
        Party alice = newParty();
        Party bob = newParty();
        Party mallory = newParty();
        service.topup(alice.userId(), alice.walletId(), money("20"), null);
        Transfer t = service.peerTransfer(alice.userId(), alice.walletId(), bob.walletId(), money("5"), null);

        assertThat(service.get(alice.userId(), t.id()).id()).isEqualTo(t.id());
        assertThat(service.get(bob.userId(), t.id()).id()).isEqualTo(t.id());
        assertThatThrownBy(() -> service.get(mallory.userId(), t.id()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("TRANSFER_NOT_FOUND"));
    }
}
