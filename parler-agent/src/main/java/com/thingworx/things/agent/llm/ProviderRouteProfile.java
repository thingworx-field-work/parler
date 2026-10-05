package com.thingworx.things.agent.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Ordered Provider route profile contract (U7 / G16 §7.1 / D8). References existing Provider
 * Thing names only — never credentials or native endpoints. JSON load/validation lands in SPR-4;
 * SPR-0 freezes the field vocabulary and budget keys.
 */
public final class ProviderRouteProfile {

    private final String id;
    private final List<String> providers;
    private final Set<ProviderCapabilityToken> requiredCapabilities;
    private final Set<String> allowedDataClassifications;
    private final int maxSameProviderRetries;
    private final int maxProvidersTried;
    private final int maxTotalAttempts;
    private final long maxCumulativeWaitMs;
    private final long maxWallTimeMs;
    private final String qualityTier;
    private final boolean fallbackVisible;

    public ProviderRouteProfile(
            String id,
            List<String> providers,
            Set<ProviderCapabilityToken> requiredCapabilities,
            Set<String> allowedDataClassifications,
            int maxSameProviderRetries,
            int maxProvidersTried,
            int maxTotalAttempts,
            long maxCumulativeWaitMs,
            long maxWallTimeMs,
            String qualityTier,
            boolean fallbackVisible) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id required");
        }
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("providers required");
        }
        this.id = id.trim();
        this.providers = Collections.unmodifiableList(new ArrayList<>(providers));
        this.requiredCapabilities = requiredCapabilities == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(requiredCapabilities));
        this.allowedDataClassifications = allowedDataClassifications == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(allowedDataClassifications));
        this.maxSameProviderRetries = maxSameProviderRetries;
        this.maxProvidersTried = maxProvidersTried;
        this.maxTotalAttempts = maxTotalAttempts;
        this.maxCumulativeWaitMs = maxCumulativeWaitMs;
        this.maxWallTimeMs = maxWallTimeMs;
        this.qualityTier = qualityTier != null ? qualityTier : "";
        this.fallbackVisible = fallbackVisible;
    }

    public String id() {
        return id;
    }

    public List<String> providers() {
        return providers;
    }

    public Set<ProviderCapabilityToken> requiredCapabilities() {
        return requiredCapabilities;
    }

    public Set<String> allowedDataClassifications() {
        return allowedDataClassifications;
    }

    public int maxSameProviderRetries() {
        return maxSameProviderRetries;
    }

    public int maxProvidersTried() {
        return maxProvidersTried;
    }

    public int maxTotalAttempts() {
        return maxTotalAttempts;
    }

    public long maxCumulativeWaitMs() {
        return maxCumulativeWaitMs;
    }

    public long maxWallTimeMs() {
        return maxWallTimeMs;
    }

    public String qualityTier() {
        return qualityTier;
    }

    public boolean fallbackVisible() {
        return fallbackVisible;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProviderRouteProfile)) {
            return false;
        }
        ProviderRouteProfile that = (ProviderRouteProfile) o;
        return maxSameProviderRetries == that.maxSameProviderRetries
                && maxProvidersTried == that.maxProvidersTried
                && maxTotalAttempts == that.maxTotalAttempts
                && maxCumulativeWaitMs == that.maxCumulativeWaitMs
                && maxWallTimeMs == that.maxWallTimeMs
                && fallbackVisible == that.fallbackVisible
                && Objects.equals(id, that.id)
                && Objects.equals(providers, that.providers)
                && Objects.equals(requiredCapabilities, that.requiredCapabilities)
                && Objects.equals(allowedDataClassifications, that.allowedDataClassifications)
                && Objects.equals(qualityTier, that.qualityTier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                id,
                providers,
                requiredCapabilities,
                allowedDataClassifications,
                maxSameProviderRetries,
                maxProvidersTried,
                maxTotalAttempts,
                maxCumulativeWaitMs,
                maxWallTimeMs,
                qualityTier,
                fallbackVisible);
    }
}
