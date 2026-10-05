package com.thingworx.things.agent.taskstate;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

class SkillChecklistContinuationMergeTest {

    @Test
    void null_agent_returns_null() {
        assertNull(SkillChecklistContinuationMerge.unionForHitlContinuation(null, List.of("A"), List.of()));
    }
}
