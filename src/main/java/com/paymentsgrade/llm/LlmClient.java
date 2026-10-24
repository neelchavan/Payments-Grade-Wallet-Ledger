package com.paymentsgrade.llm;

/** The only thing the app needs from a model: text in, text out. Callers must treat the reply as untrusted. */
public interface LlmClient {

    String complete(LlmRequest request);
}
