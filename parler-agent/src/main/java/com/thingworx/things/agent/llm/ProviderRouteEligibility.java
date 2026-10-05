package com.thingworx.things.agent.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure eligibility checks for a Provider against profile + round requirements (SPR-4 / D12 / D13).
 * Circuit admission is separate ({@link ProviderCircuitBreaker#admit}).
 *
 * <p>D13 fail-closed: when the round/profile requires a control, absent Provider effective
 * metadata is ineligible (not "assume unlimited"). An empty profile classification allowlist
 * means the route does not constrain classification; an empty Provider allowlist never means
 * "accept any classification/region."
 */
public final class ProviderRouteEligibility {

    private ProviderRouteEligibility() {}

    public static Optional<String> ineligibilityReason(
            ProviderRouteProfile profile,
            ProviderRoundRequirements requirements,
            ProviderEligibilityView provider) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(requirements, "requirements");
        Objects.requireNonNull(provider, "provider");

        if (!provider.enabled()) {
            return Optional.of("provider_disabled");
        }

        // Quality: profile non-blank tier requires Provider non-blank matching tier (D13).
        if (!profile.qualityTier().isBlank()) {
            if (provider.qualityTier().isBlank()) {
                return Optional.of("quality_tier_missing");
            }
            if (!profile.qualityTier().equalsIgnoreCase(provider.qualityTier())) {
                return Optional.of("quality_tier_mismatch");
            }
        }

        for (ProviderCapabilityToken token : unionRequired(profile, requirements)) {
            Optional<String> cap = capabilityReason(token, requirements, provider);
            if (cap.isPresent()) {
                return cap;
            }
        }

        if (!requirements.dataClassification().isBlank()) {
            // Empty profile allowlist = no route-level constraint; denial only when populated.
            if (!profile.allowedDataClassifications().isEmpty()
                    && !containsIgnoreCase(
                            profile.allowedDataClassifications(), requirements.dataClassification())) {
                return Optional.of("profile_data_classification_denied");
            }
            // Provider must declare an allowlist and include the round classification (fail closed).
            if (provider.allowedDataClassifications().isEmpty()) {
                return Optional.of("provider_data_classification_missing");
            }
            if (!containsIgnoreCase(
                    provider.allowedDataClassifications(), requirements.dataClassification())) {
                return Optional.of("provider_data_classification_denied");
            }
        }

        if (!requirements.dataEgressRegion().isBlank()) {
            // Offering DATA_EGRESS_REGION alone is insufficient — regions list must be present.
            if (provider.allowedEgressRegions().isEmpty()) {
                return Optional.of("egress_region_missing");
            }
            if (!containsIgnoreCase(provider.allowedEgressRegions(), requirements.dataEgressRegion())) {
                return Optional.of("egress_region_denied");
            }
        }

        return Optional.empty();
    }

    public static boolean isEligible(
            ProviderRouteProfile profile,
            ProviderRoundRequirements requirements,
            ProviderEligibilityView provider) {
        return ineligibilityReason(profile, requirements, provider).isEmpty();
    }

    private static List<ProviderCapabilityToken> unionRequired(
            ProviderRouteProfile profile, ProviderRoundRequirements requirements) {
        List<ProviderCapabilityToken> out = new ArrayList<>(profile.requiredCapabilities());
        for (ProviderCapabilityToken t : requirements.requiredCapabilities()) {
            if (!out.contains(t)) {
                out.add(t);
            }
        }
        if (requirements.hitlContinuationNeeded()
                && !out.contains(ProviderCapabilityToken.HITL_CONTINUATION)) {
            out.add(ProviderCapabilityToken.HITL_CONTINUATION);
        }
        if (requirements.contextTokensNeeded() > 0
                && !out.contains(ProviderCapabilityToken.CONTEXT_WINDOW)) {
            out.add(ProviderCapabilityToken.CONTEXT_WINDOW);
        }
        if (!requirements.dataEgressRegion().isBlank()
                && !out.contains(ProviderCapabilityToken.DATA_EGRESS_REGION)) {
            out.add(ProviderCapabilityToken.DATA_EGRESS_REGION);
        }
        return out;
    }

    private static Optional<String> capabilityReason(
            ProviderCapabilityToken token,
            ProviderRoundRequirements requirements,
            ProviderEligibilityView provider) {
        if (!provider.offeredCapabilities().contains(token)) {
            return Optional.of("capability_missing:" + token.name());
        }
        if (token == ProviderCapabilityToken.CONTEXT_WINDOW) {
            long need = requirements.contextTokensNeeded();
            if (need > 0 && (provider.contextWindowTokens() <= 0 || provider.contextWindowTokens() < need)) {
                return Optional.of("context_window_insufficient");
            }
        }
        if (token == ProviderCapabilityToken.HITL_CONTINUATION && requirements.hitlContinuationNeeded()
                && !provider.offeredCapabilities().contains(ProviderCapabilityToken.HITL_CONTINUATION)) {
            return Optional.of("capability_missing:HITL_CONTINUATION");
        }
        return Optional.empty();
    }

    private static boolean containsIgnoreCase(Iterable<String> values, String needle) {
        String n = needle.toLowerCase(Locale.ROOT);
        for (String v : values) {
            if (v != null && v.toLowerCase(Locale.ROOT).equals(n)) {
                return true;
            }
        }
        return false;
    }
}
