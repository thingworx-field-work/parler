package com.thingworx.things.agent.join;

import java.util.List;

/**
 * Minimal App-owned exact-join profile for TQJ-5. Real Apps replace this with versioned config
 * loaded from their Thing/Service surface; Core only validates and executes.
 */
public final class DemoExactJoinAppProfile {

    public static final String PROFILE_DIGEST = "demo-exact-join-v1";

    private DemoExactJoinAppProfile() {}

    /** Default inner 1:1 join on shared {@code id} key, build left. */
    public static ExactJoinConfig innerOneToOneOnId() {
        return ExactJoinConfig.builder()
                .joinType(JoinType.INNER)
                .cardinality(JoinCardinality.ONE_TO_ONE)
                .keys(List.of(new JoinKeySpec("id", "id")))
                .collisionPolicy(CollisionPolicy.error())
                .buildSide(BuildSide.LEFT)
                .profileDigest(PROFILE_DIGEST)
                .build();
    }

    /** Default left join on shared {@code id}; build right (required for LEFT). */
    public static ExactJoinConfig leftOneToOneOnId() {
        return ExactJoinConfig.builder()
                .joinType(JoinType.LEFT)
                .cardinality(JoinCardinality.ONE_TO_ONE)
                .keys(List.of(new JoinKeySpec("id", "id")))
                .collisionPolicy(CollisionPolicy.error())
                .buildSide(BuildSide.RIGHT)
                .profileDigest(PROFILE_DIGEST)
                .build();
    }
}
