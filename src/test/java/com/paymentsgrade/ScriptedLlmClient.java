package com.paymentsgrade;

import com.paymentsgrade.llm.LlmClient;
import com.paymentsgrade.llm.LlmRequest;
import com.paymentsgrade.llm.MockLlmClient;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Records every prompt and can replace the model's reply, to test hostile or broken output. */
public class ScriptedLlmClient implements LlmClient {

    private final MockLlmClient fallback;
    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
    private volatile Function<LlmRequest, String> script;

    public ScriptedLlmClient(MockLlmClient fallback) {
        this.fallback = fallback;
    }

    public void reply(Function<LlmRequest, String> script) {
        this.script = script;
    }

    public void reset() {
        script = null;
        requests.clear();
    }

    public List<LlmRequest> requests() {
        return requests;
    }

    @Override
    public String complete(LlmRequest request) {
        requests.add(request);
        Function<LlmRequest, String> current = script;
        return current != null ? current.apply(request) : fallback.complete(request);
    }
}
