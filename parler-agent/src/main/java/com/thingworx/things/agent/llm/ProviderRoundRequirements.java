package com.thingworx.things.agent.llm;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Round-local eligibility requirements for G16 route filtering (U7 §7.3 / D12 / D13).
 * Built from the current turn; not a pricing profile (U11).
 */
public final class ProviderRoundRequirements {

    private final Set<ProviderCapabilityToken> requiredCapabilities;
    private final String dataClassification;
    private final String dataEgressRegion;
    private final boolean hitlContinuationNeeded;
    private final long contextTokensNeeded;
    private final boolean outputAccepted;

    public ProviderRoundRequirements(
            Set<ProviderCapabilityToken> requiredCapabilities,
            String dataClassification,
            String dataEgressRegion,
            boolean hitlContinuationNeeded,
            long contextTokensNeeded,
            boolean outputAccepted) {
        this.requiredCapabilities = requiredCapabilities == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(requiredCapabilities));
        this.dataClassification = dataClassification != null ? dataClassification.trim() : "";
        this.dataEgressRegion = dataEgressRegion != null ? dataEgressRegion.trim() : "";
        this.hitlContinuationNeeded = hitlContinuationNeeded;
        this.contextTokensNeeded = Math.max(0L, contextTokensNeeded);
        this.outputAccepted = outputAccepted;
    }

    public static ProviderRoundRequirements ofCapabilities(Set<ProviderCapabilityToken> caps) {
        return new ProviderRoundRequirements(caps, "", "", false, 0L, false);
    }

    public Set<ProviderCapabilityToken> requiredCapabilities() {
        return requiredCapabilities;
    }

    public String dataClassification() {
        return dataClassification;
    }

    public String dataEgressRegion() {
        return dataEgressRegion;
    }

    public boolean hitlContinuationNeeded() {
        return hitlContinuationNeeded;
    }

    public long contextTokensNeeded() {
        return contextTokensNeeded;
    }

    /** When true, fallback to another Provider is forbidden for this round (D9). */
    public boolean outputAccepted() {
        return outputAccepted;
    }

    public ProviderRoundRequirements withOutputAccepted(boolean accepted) {
        return new ProviderRoundRequirements(
                requiredCapabilities,
                dataClassification,
                dataEgressRegion,
                hitlContinuationNeeded,
                contextTokensNeeded,
                accepted);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProviderRoundRequirements)) {
            return false;
        }
        ProviderRoundRequirements that = (ProviderRoundRequirements) o;
        return hitlContinuationNeeded == that.hitlContinuationNeeded
                && contextTokensNeeded == that.contextTokensNeeded
                && outputAccepted == that.outputAccepted
                && Objects.equals(requiredCapabilities, that.requiredCapabilities)
                && Objects.equals(dataClassification, that.dataClassification)
                && Objects.equals(dataEgressRegion, that.dataEgressRegion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                requiredCapabilities,
                dataClassification,
                dataEgressRegion,
                hitlContinuationNeeded,
                contextTokensNeeded,
                outputAccepted);
    }
}
