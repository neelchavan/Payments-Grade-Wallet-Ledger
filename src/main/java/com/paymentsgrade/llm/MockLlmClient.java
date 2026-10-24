package com.paymentsgrade.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline stand-in for a model: a small rule-based parser, so the app and tests need no key or network. */
public class MockLlmClient implements LlmClient {

    private static final Pattern PROMPT = Pattern.compile("Today's date: (\\d{4}-\\d{2}-\\d{2})\\nQuestion: (.*)", Pattern.DOTALL);
    private static final Pattern RANGE = Pattern.compile("(?:from|between)\\s+(\\d{4}-\\d{2}-\\d{2})\\s+(?:to|and)\\s+(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern LAST_DAYS = Pattern.compile("last\\s+(\\d{1,3})\\s+days");
    private static final Pattern MONTH = Pattern.compile("\\b(?:in|during|for)\\s+(" + String.join("|",
            Arrays.stream(Month.values()).map(m -> m.getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase()).toList())
            + ")(?:\\s+(\\d{4}))?");
    private static final Pattern COUNTERPARTY = Pattern.compile("\\b(?:to|paid|pay)\\s+([A-Z][\\p{L}.'-]*(?:\\s+[A-Z][\\p{L}.'-]*)*)");

    private final ObjectMapper mapper;

    public MockLlmClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String complete(LlmRequest request) {
        return switch (request.task()) {
            case PARSE_QUESTION -> parseQuestion(request.user());
            case EXPLAIN_ISSUE -> explainIssue(request.user());
        };
    }

    private String parseQuestion(String prompt) {
        Matcher m = PROMPT.matcher(prompt);
        if (!m.matches()) {
            throw new LlmException("Unrecognised prompt");
        }
        LocalDate today = LocalDate.parse(m.group(1));
        String original = m.group(2);
        String q = original.toLowerCase(Locale.ROOT);

        int type;
        String counterparty = null;
        Matcher cp = COUNTERPARTY.matcher(original);
        if (q.contains("how many") || q.contains("number of") || q.contains("count")) {
            type = 3;
        } else if (q.contains("largest") || q.contains("biggest") || q.contains("highest") || q.contains("maximum")) {
            type = 4;
        } else if (cp.find()) {
            type = 2;
            counterparty = cp.group(1);
        } else {
            type = 1;
        }

        LocalDate[] range = dateRange(q, today);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        if (counterparty != null) {
            out.put("counterparty", counterparty);
        }
        out.put("from", range[0].toString());
        out.put("to", range[1].toString());
        return json(out);
    }

    private static LocalDate[] dateRange(String q, LocalDate today) {
        Matcher explicit = RANGE.matcher(q);
        if (explicit.find()) {
            return new LocalDate[] {LocalDate.parse(explicit.group(1)), LocalDate.parse(explicit.group(2))};
        }
        if (q.contains("yesterday")) {
            return new LocalDate[] {today.minusDays(1), today.minusDays(1)};
        }
        if (q.contains("today")) {
            return new LocalDate[] {today, today};
        }
        if (q.contains("last month")) {
            LocalDate first = today.withDayOfMonth(1).minusMonths(1);
            return new LocalDate[] {first, first.withDayOfMonth(first.lengthOfMonth())};
        }
        if (q.contains("this month")) {
            return new LocalDate[] {today.withDayOfMonth(1), today};
        }
        if (q.contains("last year")) {
            return new LocalDate[] {LocalDate.of(today.getYear() - 1, 1, 1), LocalDate.of(today.getYear() - 1, 12, 31)};
        }
        if (q.contains("this year")) {
            return new LocalDate[] {LocalDate.of(today.getYear(), 1, 1), today};
        }
        Matcher days = LAST_DAYS.matcher(q);
        if (days.find()) {
            return new LocalDate[] {today.minusDays(Integer.parseInt(days.group(1))), today};
        }
        Matcher month = MONTH.matcher(q);
        if (month.find()) {
            Month named = Arrays.stream(Month.values())
                    .filter(mo -> mo.getDisplayName(TextStyle.FULL, Locale.ENGLISH).equalsIgnoreCase(month.group(1)))
                    .findFirst().orElseThrow();
            int year = month.group(2) != null ? Integer.parseInt(month.group(2))
                    : named.getValue() > today.getMonthValue() ? today.getYear() - 1 : today.getYear();
            LocalDate first = LocalDate.of(year, named, 1);
            return new LocalDate[] {first, first.withDayOfMonth(first.lengthOfMonth())};
        }
        return new LocalDate[] {today.minusDays(30), today};
    }

    private String explainIssue(String user) {
        try {
            JsonNode issue = mapper.readTree(user);
            String label = switch (issue.path("check").asText()) {
                case "GLOBAL_BALANCE", "TRANSFER_BALANCE" -> "MISSING_LEDGER_ENTRY";
                case "ACCOUNT_BALANCE" -> "BALANCE_TAMPERING";
                case "OVERDRAFT" -> "OVERDRAFT";
                case "ORPHAN" -> "ORPHANED_RECORD";
                default -> "UNKNOWN";
            };
            return json(Map.of("label", label));
        } catch (JsonProcessingException e) {
            throw new LlmException("Unrecognised prompt", e);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
