package com.thingworx.things.agent.analysis.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.analysis.config.U4DemoAppProfiles.Operation;
import com.thingworx.things.agent.join.DemoExactJoinAppProfile;
import com.thingworx.things.agent.join.U4OperationAdmission;
import com.thingworx.things.agent.tools.TabulateCachedResultToolSchema;

class U4DemoAppProfilesTest {

    @BeforeEach
    void setUp() {
        U4OperationAdmission.resetForTests();
    }

    @AfterEach
    void tearDown() {
        U4OperationAdmission.resetForTests();
    }

    @Test
    void profileDigests_matchDemoBindings() {
        assertEquals(DemoExactJoinAppProfile.PROFILE_DIGEST, U4DemoAppProfiles.profileDigest(Operation.EXACT_JOIN));
        assertEquals(Operation.QUALITY.profileDigest(), U4DemoAppProfiles.profileDigest(Operation.QUALITY));
    }

    @Test
    void fromMode_roundTrips() {
        for (Operation op : Operation.values()) {
            assertEquals(op, Operation.fromMode(op.mode()));
        }
        assertThrows(IllegalArgumentException.class, () -> Operation.fromMode("banana"));
    }

    @Test
    void advertisedU4Modes_respectsAdmission() {
        List<String> all = U4DemoAppProfiles.advertisedU4Modes();
        assertEquals(6, all.size());
        assertTrue(all.contains(TabulateCachedResultToolSchema.MODE_PERIOD_COMPARE));

        U4OperationAdmission.setPeriodCompareEnabled(false);
        U4OperationAdmission.setQualityEnabled(false);
        List<String> narrowed = U4DemoAppProfiles.advertisedU4Modes();
        assertFalse(narrowed.contains(TabulateCachedResultToolSchema.MODE_PERIOD_COMPARE));
        assertFalse(narrowed.contains(TabulateCachedResultToolSchema.MODE_QUALITY));
        assertEquals(4, narrowed.size());
    }
}
