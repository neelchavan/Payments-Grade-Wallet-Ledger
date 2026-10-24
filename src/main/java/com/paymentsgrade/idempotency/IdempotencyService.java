package com.paymentsgrade.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.paymentsgrade.idempotency.IdempotencyRepository.Row;
import com.paymentsgrade.web.ApiError;
import com.paymentsgrade.web.ApiException;
import com.paymentsgrade.web.RequestIdFilter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a POST handler at most once per (user, key).
 * Key claim, handler and saved response share one transaction: a business 4xx is rolled back to a savepoint
 * and saved on the key, while any other failure rolls back everything, including the key, so the client can retry.
 */
@Service
public class IdempotencyService {

    public static final String KEY_HEADER = "Idempotency-Key";
    public static final String REPLAY_HEADER = "Idempotent-Replayed";

    public record Result(HttpStatus status, Object body) {
        public static Result created(Object body) {
            return new Result(HttpStatus.CREATED, body);
        }
    }

    private final IdempotencyRepository keys;
    private final ObjectMapper mapper;
    private final TransactionTemplate savepoint;

    public IdempotencyService(IdempotencyRepository keys, ObjectMapper mapper, PlatformTransactionManager tm) {
        this.keys = keys;
        this.mapper = mapper;
        this.savepoint = new TransactionTemplate(tm);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /**
     * @param userId    scope of the key; null for unauthenticated and admin calls
     * @param operation identifies the endpoint so one key cannot be replayed against another endpoint
     * @param request   the parsed request body, or null
     */
    @Transactional
    public ResponseEntity<String> execute(Long userId, String rawKey, String operation, Object request,
            Supplier<Result> handler) {
        UUID key = parseKey(rawKey);
        String hash = requestHash(operation, request);

        Long id;
        try {
            id = keys.claim(userId, key, hash).orElse(null);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found");
        }
        if (id == null) {
            return replay(keys.find(userId, key), hash);
        }

        int status;
        String body;
        try {
            Result result = savepoint.execute(s -> handler.get());
            status = result.status().value();
            body = toJson(result.body());
        } catch (ApiException e) {
            if (!e.status().is4xxClientError()) {
                throw e;
            }
            status = e.status().value();
            body = toJson(new ApiError(e.code(), e.getMessage(), MDC.get(RequestIdFilter.MDC_KEY)));
        }
        keys.complete(id, status, body);
        return response(status, body, false);
    }

    private ResponseEntity<String> replay(Row saved, String hash) {
        if (!"COMPLETED".equals(saved.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_IN_PROGRESS",
                    "A request with this Idempotency-Key is still being processed");
        }
        if (!saved.requestHash().equals(hash)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used with a different request");
        }
        return response(saved.responseStatus(), saved.responseBody(), true);
    }

    private static ResponseEntity<String> response(int status, String body, boolean replayed) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        if (replayed) {
            builder.header(REPLAY_HEADER, "true");
        }
        return builder.body(body);
    }

    private static UUID parseKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Missing required header: " + KEY_HEADER);
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    KEY_HEADER + " must be a UUID");
        }
    }

    // SHA-256 over the operation and a canonical form of the body, so key order, spacing and 10 vs 10.00 do not matter.
    String requestHash(String operation, Object request) {
        StringBuilder canonical = new StringBuilder(operation).append('\n');
        appendCanonical(mapper.valueToTree(request), canonical);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void appendCanonical(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isObject()) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> field : node.properties().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(TextNode.valueOf(field.getKey())).append(':');
                appendCanonical(field.getValue(), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                appendCanonical(node.get(i), out);
            }
            out.append(']');
        } else if (node.isNumber()) {
            out.append(node.decimalValue().stripTrailingZeros().toPlainString());
        } else {
            out.append(node);
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize response", e);
        }
    }
}
