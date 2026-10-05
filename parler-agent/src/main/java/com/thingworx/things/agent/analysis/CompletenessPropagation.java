package com.thingworx.things.agent.analysis;

import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;

/**
 * Monotone-conservative completeness merge for U4 transforms and joins (B6). Never promotes
 * {@link CompletenessStatus#PARTIAL} or {@link CompletenessStatus#UNKNOWN} to
 * {@link CompletenessStatus#COMPLETE}.
 *
 * <p>Gap-bearing transforms over a proven-{@code COMPLETE} parent that fully scanned the declared
 * window may remain {@code COMPLETE} for that window/policy; observation gaps are quality findings,
 * not an automatic completeness downgrade.
 */
public final class CompletenessPropagation {

    private CompletenessPropagation() {}

    /** Worst-of merge across parents (joins) or a single parent (transforms). */
    public static CompletenessStatus mergeParents(CompletenessStatus... parents) {
        if (parents == null || parents.length == 0) {
            return CompletenessStatus.UNKNOWN;
        }
        CompletenessStatus worst = CompletenessStatus.COMPLETE;
        for (CompletenessStatus p : parents) {
            worst = worse(worst, p == null ? CompletenessStatus.UNKNOWN : p);
        }
        return worst;
    }

    /**
     * Result completeness for a transform that applied a declared window/policy over parent input.
     *
     * @param parent parent artifact completeness
     * @param inputsFullyScanned whether the declared window was fully scanned under budget
     * @param windowAndPolicyProven whether the applied window and missing policy are proven
     */
    public static CompletenessStatus forTransform(
            CompletenessStatus parent,
            boolean inputsFullyScanned,
            boolean windowAndPolicyProven) {
        CompletenessStatus base = parent == null ? CompletenessStatus.UNKNOWN : parent;
        if (!inputsFullyScanned || !windowAndPolicyProven) {
            return worse(base, CompletenessStatus.UNKNOWN);
        }
        return base;
    }

    public static CompletenessStatus worse(CompletenessStatus a, CompletenessStatus b) {
        CompletenessStatus left = a == null ? CompletenessStatus.UNKNOWN : a;
        CompletenessStatus right = b == null ? CompletenessStatus.UNKNOWN : b;
        if (left == CompletenessStatus.UNKNOWN || right == CompletenessStatus.UNKNOWN) {
            return CompletenessStatus.UNKNOWN;
        }
        if (left == CompletenessStatus.PARTIAL || right == CompletenessStatus.PARTIAL) {
            return CompletenessStatus.PARTIAL;
        }
        return CompletenessStatus.COMPLETE;
    }

    /** True when {@code candidate} is not stricter/better than {@code parent} (no upgrade). */
    public static boolean isMonotone(CompletenessStatus parent, CompletenessStatus candidate) {
        CompletenessStatus p = parent == null ? CompletenessStatus.UNKNOWN : parent;
        CompletenessStatus c = candidate == null ? CompletenessStatus.UNKNOWN : candidate;
        if (p == CompletenessStatus.UNKNOWN) {
            return c == CompletenessStatus.UNKNOWN;
        }
        if (p == CompletenessStatus.PARTIAL) {
            return c == CompletenessStatus.PARTIAL || c == CompletenessStatus.UNKNOWN;
        }
        return true;
    }
}
