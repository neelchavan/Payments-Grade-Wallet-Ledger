package com.paymentsgrade.llm;

public record LlmRequest(Task task, String system, String user) {

    public enum Task {
        PARSE_QUESTION, EXPLAIN_ISSUE
    }
}
