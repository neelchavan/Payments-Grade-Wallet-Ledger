package com.paymentsgrade.transfer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record Transfer(long id, TransferType transferType, TransferStatus status, long fromAccountId,
        long toAccountId, BigDecimal amount, String note, Long reversesTransferId, OffsetDateTime createdAt) {
}
