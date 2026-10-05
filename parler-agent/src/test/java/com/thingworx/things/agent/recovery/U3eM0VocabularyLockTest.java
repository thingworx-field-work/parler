package com.thingworx.things.agent.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.evidence.EvidenceStatus;
import com.thingworx.things.agent.execution.BudgetVector;
import com.thingworx.things.agent.execution.ExecutionScope;
import com.thingworx.things.agent.execution.RunInvocationContext;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.taskstate.TaskStateErrorCode;

/**
 * Vocabulary lock for evidence and recovery: task-state error codes, source-descriptor completeness
 * values, the retry budget key, recovery action types, and assessment status names are compatibility
 * identities that must not be silently replaced.
 */
class U3eM0VocabularyLockTest {

    private static final Set<String> EG4_STATUS = Set.of(
            "SUCCESS", "NO_FINDING", "INSUFFICIENT_EVIDENCE", "ERROR");

    @Test
    void taskStateErrorCodes_includeFirstLedgerAndTimeoutCandidates() {
        EnumSet<TaskStateErrorCode> all = EnumSet.allOf(TaskStateErrorCode.class);
        assertTrue(all.contains(TaskStateErrorCode.CACHE_MISS));
        assertTrue(all.contains(TaskStateErrorCode.UPSTREAM_TIMEOUT));
        assertTrue(all.contains(TaskStateErrorCode.PERMISSION_DENIED));
        assertTrue(all.contains(TaskStateErrorCode.PARAMETER_INVALID));
    }

    @Test
    void completenessEnum_isSoleSourceDescriptorVocabulary() {
        EnumSet<SourceDescriptor.CompletenessStatus> all =
                EnumSet.allOf(SourceDescriptor.CompletenessStatus.class);
        assertEquals(EnumSet.of(
                SourceDescriptor.CompletenessStatus.COMPLETE,
                SourceDescriptor.CompletenessStatus.PARTIAL,
                SourceDescriptor.CompletenessStatus.UNKNOWN), all);
    }

    @Test
    void runInvocationContext_retryBudgetKeyNullByDefault_andWithKeyWorks() {
        RunInvocationContext ctx = RunInvocationContext.create(ExecutionScope.CONVERSATION,
                BudgetVector.defaultsForTabular());
        assertNull(ctx.retryBudgetKey());
        assertEquals(ErrorRecoveryMapper.BUDGET_KEY_SOURCE_QUERY,
                ctx.withRetryBudgetKey(ErrorRecoveryMapper.BUDGET_KEY_SOURCE_QUERY).retryBudgetKey());
    }

    @Test
    void eg2RecoveryActionType_closedSetBoundToProductionEnum() {
        Set<String> names = EnumSet.allOf(RecoveryActionType.class).stream().map(Enum::name)
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "PATCH_ARGUMENT",
                "RESOLVE_IDENTITY",
                "REEXECUTE_SOURCE",
                "RETRY_SAME_CALL",
                "ASK_USER",
                "STOP_WITH_EVIDENCE"), names);
    }

    @Test
    void eg4AssessmentStatusNames_closedSet() {
        assertEquals(4, EG4_STATUS.size());
        assertTrue(EG4_STATUS.contains("NO_FINDING"));
        assertTrue(EG4_STATUS.contains("INSUFFICIENT_EVIDENCE"));
        Set<String> production = EnumSet.allOf(EvidenceStatus.class).stream().map(Enum::name)
                .collect(Collectors.toSet());
        assertEquals(EG4_STATUS, production);
    }

}
