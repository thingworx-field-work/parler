package com.thingworx.things.agent.llm;

/**
 * Provider-neutral max-output and reasoning resolution ({@code docs/agent/llm-api-provider-parameters.md} §6).
 */
public final class ProviderRequestResolution {

    private ProviderRequestResolution() {
    }

    /**
     * Validates a resolved max-output value before use in HTTP payloads or telemetry.
     *
     * @return a positive {@code int}; never {@code 0} or negative
     * @throws IllegalStateException when {@code resolvedValue <= 0}
     * @throws IllegalArgumentException when {@code resolvedValue > Integer.MAX_VALUE}
     */
    public static int toPositiveResolvedMaxOutput(long resolvedValue) {
        if (resolvedValue <= 0) {
            throw new IllegalStateException(
                    "requestedMaxOutputTokens must be positive (resolve via Provider bridge)");
        }
        if (resolvedValue > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "requestedMaxOutputTokens exceeds Integer.MAX_VALUE: " + resolvedValue);
        }
        return (int) resolvedValue;
    }

    /**
     * @param tableValue Provider table value; {@code <= 0} means unset
     * @param codeDefault positive fallback for this Provider shape when request and table are unset
     * @return a positive integer
     */
    public static int resolveMaxOutput(long requestValue, int tableValue, int codeDefault) {
        if (requestValue > 0) {
            return toPositiveResolvedMaxOutput(requestValue);
        }
        if (tableValue > 0) {
            return tableValue;
        }
        if (codeDefault <= 0) {
            throw new IllegalStateException("provider code default max output must be positive");
        }
        return codeDefault;
    }

    /**
     * @param codeDefault used when request and table are blank; may be {@code null} to omit
     * @return non-blank resolved value, or {@code null} to omit from the upstream request
     */
    public static String resolveReasoning(String requestValue, String tableValue, String codeDefault) {
        if (requestValue != null && !requestValue.isBlank()) {
            return requestValue.trim();
        }
        if (tableValue != null && !tableValue.isBlank()) {
            return tableValue.trim();
        }
        if (codeDefault != null && !codeDefault.isBlank()) {
            return codeDefault.trim();
        }
        return null;
    }

    /** Table/config value {@code <= 0} is treated as unset. */
    public static int positiveOrDefault(int configuredValue, int defaultWhenUnset) {
        return configuredValue > 0 ? configuredValue : defaultWhenUnset;
    }
}
