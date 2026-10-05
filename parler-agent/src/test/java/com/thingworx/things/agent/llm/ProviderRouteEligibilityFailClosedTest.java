package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * D13 missing effective metadata must fail closed (not assume unlimited).
 */
class ProviderRouteEligibilityFailClosedTest {

    @Test
    void blankProviderQualityFailsWhenProfileRequiresTier() {
        ProviderRouteProfile profile = profile("APPROVED_INTERACTIVE", Set.of("INTERNAL"));
        ProviderRoundRequirements req = ProviderRoundRequirements.ofCapabilities(
                Set.of(ProviderCapabilityToken.TOOLS));
        ProviderEligibilityView blankQuality = new ProviderEligibilityView(
                "P1",
                true,
                Set.of(ProviderCapabilityToken.TOOLS),
                Set.of("INTERNAL"),
                Set.of(),
                0L,
                "");
        assertEquals(
                "quality_tier_missing",
                ProviderRouteEligibility.ineligibilityReason(profile, req, blankQuality).orElse(""));
    }

    @Test
    void emptyProviderClassificationAllowlistFailsWhenRoundHasClassification() {
        ProviderRouteProfile profile = profile("APPROVED_INTERACTIVE", Set.of()); // empty profile = no constraint
        ProviderRoundRequirements req = new ProviderRoundRequirements(
                Set.of(ProviderCapabilityToken.TOOLS), "INTERNAL", "", false, 0L, false);
        ProviderEligibilityView noClass = new ProviderEligibilityView(
                "P1",
                true,
                Set.of(ProviderCapabilityToken.TOOLS),
                Set.of(),
                Set.of(),
                0L,
                "APPROVED_INTERACTIVE");
        assertEquals(
                "provider_data_classification_missing",
                ProviderRouteEligibility.ineligibilityReason(profile, req, noClass).orElse(""));
    }

    @Test
    void emptyProviderEgressRegionsFailsWhenRoundRequiresRegion() {
        ProviderRouteProfile profile = profile("APPROVED_INTERACTIVE", Set.of("INTERNAL"));
        ProviderRoundRequirements req = new ProviderRoundRequirements(
                Set.of(ProviderCapabilityToken.TOOLS),
                "INTERNAL",
                "US",
                false,
                0L,
                false);
        ProviderEligibilityView tokenButNoRegions = new ProviderEligibilityView(
                "P1",
                true,
                Set.of(ProviderCapabilityToken.TOOLS, ProviderCapabilityToken.DATA_EGRESS_REGION),
                Set.of("INTERNAL"),
                Set.of(),
                0L,
                "APPROVED_INTERACTIVE");
        assertEquals(
                "egress_region_missing",
                ProviderRouteEligibility.ineligibilityReason(profile, req, tokenButNoRegions).orElse(""));
    }

    @Test
    void populatedMismatchStillDenied() {
        ProviderRouteProfile profile = profile("APPROVED_INTERACTIVE", Set.of("INTERNAL"));
        ProviderRoundRequirements req = new ProviderRoundRequirements(
                Set.of(ProviderCapabilityToken.TOOLS),
                "INTERNAL",
                "US",
                false,
                0L,
                false);
        ProviderEligibilityView wrong = new ProviderEligibilityView(
                "P1",
                true,
                Set.of(ProviderCapabilityToken.TOOLS, ProviderCapabilityToken.DATA_EGRESS_REGION),
                Set.of("PUBLIC"),
                Set.of("EU"),
                0L,
                "BATCH_ONLY");
        assertEquals(
                "quality_tier_mismatch",
                ProviderRouteEligibility.ineligibilityReason(profile, req, wrong).orElse(""));
        ProviderEligibilityView classDenied = new ProviderEligibilityView(
                "P2",
                true,
                Set.of(ProviderCapabilityToken.TOOLS, ProviderCapabilityToken.DATA_EGRESS_REGION),
                Set.of("PUBLIC"),
                Set.of("US"),
                0L,
                "APPROVED_INTERACTIVE");
        assertEquals(
                "provider_data_classification_denied",
                ProviderRouteEligibility.ineligibilityReason(profile, req, classDenied).orElse(""));
    }

    @Test
    void eligibleWhenMetadataPresentAndMatches() {
        ProviderRouteProfile profile = profile("APPROVED_INTERACTIVE", Set.of("INTERNAL"));
        ProviderRoundRequirements req = new ProviderRoundRequirements(
                Set.of(ProviderCapabilityToken.TOOLS),
                "INTERNAL",
                "US",
                false,
                0L,
                false);
        ProviderEligibilityView ok = new ProviderEligibilityView(
                "P1",
                true,
                Set.of(ProviderCapabilityToken.TOOLS, ProviderCapabilityToken.DATA_EGRESS_REGION),
                Set.of("INTERNAL"),
                Set.of("US"),
                0L,
                "APPROVED_INTERACTIVE");
        assertTrue(ProviderRouteEligibility.isEligible(profile, req, ok));
    }

    private static ProviderRouteProfile profile(String qualityTier, Set<String> classifications) {
        return new ProviderRouteProfile(
                "r1",
                List.of("P1"),
                Set.of(ProviderCapabilityToken.TOOLS),
                classifications,
                1,
                1,
                3,
                30_000L,
                60_000L,
                qualityTier,
                true);
    }
}
