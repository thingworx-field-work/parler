package com.thingworx.things.agent.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic degraded outcome when no Provider route remains (U7 §7.5 / D14).
 * Never invents tool calls, actions, causal claims, or new calculations.
 */
public final class ProviderDegradedOutcome {

    public static final String CODE = "PROVIDER_ROUTE_EXHAUSTED";

    private final String code;
    private final String summary;
    private final boolean fallbackVisible;
    private final boolean evidenceStillUsable;
    private final List<String> safeNextActions;
    private final List<String> triedProviders;
    private final String lastFailureClass;

    public ProviderDegradedOutcome(
            String summary,
            boolean fallbackVisible,
            boolean evidenceStillUsable,
            List<String> safeNextActions,
            List<String> triedProviders,
            String lastFailureClass) {
        this.code = CODE;
        this.summary = summary != null ? summary : "";
        this.fallbackVisible = fallbackVisible;
        this.evidenceStillUsable = evidenceStillUsable;
        this.safeNextActions = safeNextActions == null ? List.of() : List.copyOf(safeNextActions);
        this.triedProviders = triedProviders == null ? List.of() : List.copyOf(triedProviders);
        this.lastFailureClass = lastFailureClass != null ? lastFailureClass : "";
    }

    public static ProviderDegradedOutcome exhausted(
            ProviderRouteProfile profile,
            List<String> triedProviders,
            LlmProviderFailureClass lastFailure,
            boolean evidenceStillUsable) {
        List<String> actions = new ArrayList<>();
        actions.add("retry_later");
        actions.add("contact_operator");
        String cls = lastFailure != null ? lastFailure.name() : "";
        String summary = "LLM Provider route exhausted"
                + (cls.isBlank() ? "" : " after " + cls)
                + "; completed deterministic evidence is preserved when available; "
                + "no new interpretation or actions were invented.";
        return new ProviderDegradedOutcome(
                summary,
                profile != null && profile.fallbackVisible(),
                evidenceStillUsable,
                actions,
                triedProviders,
                cls);
    }

    public String code() {
        return code;
    }

    public String summary() {
        return summary;
    }

    public boolean fallbackVisible() {
        return fallbackVisible;
    }

    public boolean evidenceStillUsable() {
        return evidenceStillUsable;
    }

    public List<String> safeNextActions() {
        return safeNextActions;
    }

    public List<String> triedProviders() {
        return triedProviders;
    }

    public String lastFailureClass() {
        return lastFailureClass;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProviderDegradedOutcome)) {
            return false;
        }
        ProviderDegradedOutcome that = (ProviderDegradedOutcome) o;
        return fallbackVisible == that.fallbackVisible
                && evidenceStillUsable == that.evidenceStillUsable
                && Objects.equals(code, that.code)
                && Objects.equals(summary, that.summary)
                && Objects.equals(safeNextActions, that.safeNextActions)
                && Objects.equals(triedProviders, that.triedProviders)
                && Objects.equals(lastFailureClass, that.lastFailureClass);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                code, summary, fallbackVisible, evidenceStillUsable, safeNextActions, triedProviders, lastFailureClass);
    }
}
