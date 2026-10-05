package com.thingworx.things.agent.join;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Validated G8 exact-join configuration. Supplied invalid values fail fast at construction — never
 * silently coerced (same class-level rule as {@code QualityProfile}).
 *
 * <p>V1 rejects {@code n:n} by omitting it from {@link JoinCardinality}. Spill-to-disk is not a
 * config option.
 */
public final class ExactJoinConfig {

    private final JoinType joinType;
    private final JoinCardinality cardinality;
    private final List<JoinKeySpec> keys;
    private final CollisionPolicy collisionPolicy;
    private final BuildSide buildSide;
    private final long maxBuildRows;
    private final long maxOutputRows;
    private final String profileDigest;

    private ExactJoinConfig(Builder b) {
        this.joinType = Objects.requireNonNull(b.joinType, "joinType");
        this.cardinality = Objects.requireNonNull(b.cardinality, "cardinality");
        if (b.keys == null || b.keys.isEmpty()) {
            throw new IllegalArgumentException("keys required");
        }
        List<JoinKeySpec> copy = new ArrayList<>(b.keys.size());
        Set<String> leftSeen = new HashSet<>();
        Set<String> rightSeen = new HashSet<>();
        for (JoinKeySpec key : b.keys) {
            if (key == null) {
                throw new IllegalArgumentException("keys must not contain null");
            }
            if (!leftSeen.add(key.leftColumn())) {
                throw new IllegalArgumentException("duplicate left key column: " + key.leftColumn());
            }
            if (!rightSeen.add(key.rightColumn())) {
                throw new IllegalArgumentException("duplicate right key column: " + key.rightColumn());
            }
            copy.add(key);
        }
        this.keys = List.copyOf(copy);
        this.collisionPolicy = Objects.requireNonNull(b.collisionPolicy, "collisionPolicy");
        this.buildSide = Objects.requireNonNull(b.buildSide, "buildSide");
        // LEFT join must index the right and stream the left (unmatched-left emission).
        if (this.joinType == JoinType.LEFT && this.buildSide == BuildSide.LEFT) {
            throw new IllegalArgumentException(
                    "LEFT join requires BuildSide.RIGHT (bounded hash on right, stream left probe)");
        }
        if (b.maxBuildRows < 1L) {
            throw new IllegalArgumentException("maxBuildRows must be >= 1");
        }
        if (b.maxOutputRows < 1L) {
            throw new IllegalArgumentException("maxOutputRows must be >= 1");
        }
        this.maxBuildRows = b.maxBuildRows;
        this.maxOutputRows = b.maxOutputRows;
        if (b.profileDigest == null || b.profileDigest.isBlank()) {
            throw new IllegalArgumentException("profileDigest required");
        }
        this.profileDigest = b.profileDigest.trim();
    }

    public static Builder builder() {
        return new Builder();
    }

    public JoinType joinType() {
        return joinType;
    }

    public JoinCardinality cardinality() {
        return cardinality;
    }

    public List<JoinKeySpec> keys() {
        return keys;
    }

    public CollisionPolicy collisionPolicy() {
        return collisionPolicy;
    }

    public BuildSide buildSide() {
        return buildSide;
    }

    public long maxBuildRows() {
        return maxBuildRows;
    }

    public long maxOutputRows() {
        return maxOutputRows;
    }

    public String profileDigest() {
        return profileDigest;
    }

    public static final class Builder {
        private JoinType joinType = JoinType.INNER;
        private JoinCardinality cardinality = JoinCardinality.ONE_TO_ONE;
        private List<JoinKeySpec> keys;
        private CollisionPolicy collisionPolicy = CollisionPolicy.error();
        private BuildSide buildSide = BuildSide.LEFT;
        private long maxBuildRows = 5_000L;
        private long maxOutputRows = 5_000L;
        private String profileDigest = "exact-join-v1";

        public Builder joinType(JoinType v) {
            this.joinType = v;
            return this;
        }

        public Builder cardinality(JoinCardinality v) {
            this.cardinality = v;
            return this;
        }

        public Builder keys(List<JoinKeySpec> v) {
            this.keys = v;
            return this;
        }

        public Builder collisionPolicy(CollisionPolicy v) {
            this.collisionPolicy = v;
            return this;
        }

        public Builder buildSide(BuildSide v) {
            this.buildSide = v;
            return this;
        }

        public Builder maxBuildRows(long v) {
            this.maxBuildRows = v;
            return this;
        }

        public Builder maxOutputRows(long v) {
            this.maxOutputRows = v;
            return this;
        }

        public Builder profileDigest(String v) {
            this.profileDigest = v;
            return this;
        }

        public ExactJoinConfig build() {
            return new ExactJoinConfig(this);
        }
    }
}
