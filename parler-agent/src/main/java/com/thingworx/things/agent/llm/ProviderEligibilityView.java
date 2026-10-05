package com.thingworx.things.agent.llm;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Sanitized Provider Thing metadata for route eligibility (SPR-4). Does not carry credentials
 * or native endpoints — only policy/capability facts the route planner may use.
 */
public final class ProviderEligibilityView {

    private final String providerThingName;
    private final boolean enabled;
    private final Set<ProviderCapabilityToken> offeredCapabilities;
    private final Set<String> allowedDataClassifications;
    private final Set<String> allowedEgressRegions;
    private final long contextWindowTokens;
    private final String qualityTier;

    public ProviderEligibilityView(
            String providerThingName,
            boolean enabled,
            Set<ProviderCapabilityToken> offeredCapabilities,
            Set<String> allowedDataClassifications,
            Set<String> allowedEgressRegions,
            long contextWindowTokens,
            String qualityTier) {
        if (providerThingName == null || providerThingName.isBlank()) {
            throw new IllegalArgumentException("providerThingName required");
        }
        this.providerThingName = providerThingName.trim();
        this.enabled = enabled;
        this.offeredCapabilities = offeredCapabilities == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(offeredCapabilities));
        this.allowedDataClassifications = allowedDataClassifications == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(allowedDataClassifications));
        this.allowedEgressRegions = allowedEgressRegions == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(allowedEgressRegions));
        this.contextWindowTokens = Math.max(0L, contextWindowTokens);
        this.qualityTier = qualityTier != null ? qualityTier : "";
    }

    public String providerThingName() {
        return providerThingName;
    }

    public boolean enabled() {
        return enabled;
    }

    public Set<ProviderCapabilityToken> offeredCapabilities() {
        return offeredCapabilities;
    }

    public Set<String> allowedDataClassifications() {
        return allowedDataClassifications;
    }

    public Set<String> allowedEgressRegions() {
        return allowedEgressRegions;
    }

    /** {@code 0} means unknown — CONTEXT_WINDOW requirement fails closed. */
    public long contextWindowTokens() {
        return contextWindowTokens;
    }

    public String qualityTier() {
        return qualityTier;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProviderEligibilityView)) {
            return false;
        }
        ProviderEligibilityView that = (ProviderEligibilityView) o;
        return enabled == that.enabled
                && contextWindowTokens == that.contextWindowTokens
                && Objects.equals(providerThingName, that.providerThingName)
                && Objects.equals(offeredCapabilities, that.offeredCapabilities)
                && Objects.equals(allowedDataClassifications, that.allowedDataClassifications)
                && Objects.equals(allowedEgressRegions, that.allowedEgressRegions)
                && Objects.equals(qualityTier, that.qualityTier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                providerThingName,
                enabled,
                offeredCapabilities,
                allowedDataClassifications,
                allowedEgressRegions,
                contextWindowTokens,
                qualityTier);
    }
}
