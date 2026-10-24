package com.paymentsgrade.user;

import java.time.OffsetDateTime;

public record User(long id, String name, String email, String phone, String kycStatus, OffsetDateTime createdAt) {
}
