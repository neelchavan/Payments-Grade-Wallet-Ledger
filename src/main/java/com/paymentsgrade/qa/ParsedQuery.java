package com.paymentsgrade.qa;

import java.time.LocalDate;

public record ParsedQuery(Type type, String counterparty, LocalDate from, LocalDate to) {

    public enum Type {
        TOTAL_SPENT(1), SENT_TO_COUNTERPARTY(2), TRANSFER_COUNT(3), LARGEST_TRANSFER(4);

        private final int code;

        Type(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        static Type of(int code) {
            for (Type type : values()) {
                if (type.code == code) {
                    return type;
                }
            }
            throw new IllegalArgumentException("unknown type");
        }
    }
}
