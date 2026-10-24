package com.paymentsgrade.qa;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.paymentsgrade.web.ApiException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Model output is untrusted. Only a tiny, exactly-shaped JSON object is accepted; anything else is rejected
 * without echoing it. The validated values are only ever used as bind parameters.
 */
@Component
public class QueryValidator {

    private static final Set<String> ALLOWED_KEYS = Set.of("type", "counterparty", "from", "to");
    private static final int MAX_OUTPUT_CHARS = 2000;
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern NAME = Pattern.compile("\\p{L}[\\p{L} .'-]{0,99}");
    private static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);
    private static final long MAX_RANGE_DAYS = 3660;

    private final ObjectMapper strict = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public ParsedQuery validate(String raw, LocalDate today) {
        try {
            return doValidate(raw, today);
        } catch (RuntimeException | java.io.IOException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_QUESTION",
                    "I can only answer questions about your own spending, transfer counts and largest transfers");
        }
    }

    private ParsedQuery doValidate(String raw, LocalDate today) throws java.io.IOException {
        String text = stripFence(raw == null ? "" : raw.trim());
        if (text.isEmpty() || text.length() > MAX_OUTPUT_CHARS) {
            throw new IllegalArgumentException("size");
        }
        JsonNode node = strict.readTree(text);
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("shape");
        }
        node.fieldNames().forEachRemaining(name -> {
            if (!ALLOWED_KEYS.contains(name)) {
                throw new IllegalArgumentException("key");
            }
        });

        JsonNode type = node.get("type");
        if (type == null || !type.isIntegralNumber() || !type.canConvertToInt()) {
            throw new IllegalArgumentException("type");
        }
        ParsedQuery.Type queryType = ParsedQuery.Type.of(type.intValue());

        String counterparty = null;
        JsonNode cp = node.get("counterparty");
        boolean hasCounterparty = cp != null && !cp.isNull();
        if (queryType == ParsedQuery.Type.SENT_TO_COUNTERPARTY) {
            if (!hasCounterparty || !cp.isTextual() || !NAME.matcher(cp.textValue().trim()).matches()) {
                throw new IllegalArgumentException("counterparty");
            }
            counterparty = cp.textValue().trim();
        } else if (hasCounterparty) {
            throw new IllegalArgumentException("counterparty not allowed");
        }

        LocalDate from = date(node.get("from"));
        LocalDate to = date(node.get("to"));
        if (from.isAfter(to) || from.isBefore(EARLIEST) || to.isAfter(today.plusDays(366))
                || ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("range");
        }
        return new ParsedQuery(queryType, counterparty, from, to);
    }

    private static LocalDate date(JsonNode node) {
        if (node == null || !node.isTextual() || !DATE.matcher(node.textValue()).matches()) {
            throw new IllegalArgumentException("date");
        }
        try {
            return LocalDate.parse(node.textValue());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("date", e);
        }
    }

    // Models often wrap JSON in a markdown fence even when told not to.
    private static String stripFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            throw new IllegalArgumentException("fence");
        }
        return text.substring(firstNewline + 1, lastFence).trim();
    }
}
