package com.paymentsgrade.user;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import com.paymentsgrade.web.ApiException;

@Repository
public class UserRepository {

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public User insert(String name, String email, String phone) {
        try {
            return jdbc.sql("""
                    INSERT INTO users (name, email, phone) VALUES (:name, :email, :phone)
                    RETURNING id, name, email, phone, kyc_status, created_at""")
                    .param("name", name).param("email", email).param("phone", phone)
                    .query((rs, i) -> new User(rs.getLong("id"), rs.getString("name"), rs.getString("email"),
                            rs.getString("phone"), rs.getString("kyc_status"),
                            rs.getObject("created_at", java.time.OffsetDateTime.class)))
                    .single();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_EXISTS", "Email or phone is already registered");
        }
    }
}
