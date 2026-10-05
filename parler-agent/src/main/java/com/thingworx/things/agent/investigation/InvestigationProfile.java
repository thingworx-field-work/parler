package com.thingworx.things.agent.investigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reviewed G7 investigation profile: closed evidence-test list and next-check templates.
 * Missing reviewed weights → lexicographic test order (D7).
 */
public final class InvestigationProfile {

    private final String profileId;
    private final List<EvidenceTestSpec> tests;
    private final boolean useNumericWeights;

    private InvestigationProfile(String profileId, List<EvidenceTestSpec> tests, boolean useNumericWeights) {
        this.profileId = requireNonBlank(profileId, "profileId");
        if (tests == null || tests.isEmpty()) {
            throw new IllegalArgumentException("tests required");
        }
        Map<String, EvidenceTestSpec> byId = new LinkedHashMap<>();
        for (EvidenceTestSpec t : tests) {
            Objects.requireNonNull(t, "test");
            if (byId.put(t.testId(), t) != null) {
                throw new IllegalArgumentException("duplicate testId " + t.testId());
            }
        }
        List<EvidenceTestSpec> ordered = new ArrayList<>(byId.values());
        ordered.sort(Comparator.comparingInt(EvidenceTestSpec::lexicographicOrder)
                .thenComparing(EvidenceTestSpec::testId));
        this.tests = Collections.unmodifiableList(ordered);
        this.useNumericWeights = useNumericWeights;
    }

    public static InvestigationProfile of(String profileId, List<EvidenceTestSpec> tests, boolean useNumericWeights) {
        return new InvestigationProfile(profileId, tests, useNumericWeights);
    }

    public String profileId() {
        return profileId;
    }

    public List<EvidenceTestSpec> tests() {
        return tests;
    }

    public boolean useNumericWeights() {
        return useNumericWeights;
    }

    public EvidenceTestSpec requireTest(String testId) {
        for (EvidenceTestSpec t : tests) {
            if (t.testId().equals(testId)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown testId " + testId);
    }

    private static String requireNonBlank(String s, String name) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        return s.trim();
    }
}
