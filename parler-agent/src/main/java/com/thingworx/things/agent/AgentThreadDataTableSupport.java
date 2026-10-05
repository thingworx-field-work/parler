package com.thingworx.things.agent;

import org.joda.time.DateTime;

import com.thingworx.entities.RootEntity;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.Thing;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.StringPrimitive;
import com.thingworx.webservices.context.ThreadLocalContext;
import com.thingworx.security.context.SecurityContext;

/**
 * Shared lookups on {@value #THREAD_DATA_TABLE_NAME} for conversation ownership (Phase F {@link ParlerGateway}).
 */
public final class AgentThreadDataTableSupport {

    public static final String THREAD_DATA_TABLE_NAME = "AgentThreadDataTable";

    private AgentThreadDataTableSupport() {}

    /**
     * Ensures {@code conversationId} exists in {@value #THREAD_DATA_TABLE_NAME}, the row has a non-blank {@code username},
     * and it matches the caller.
     * No-op when {@code conversationId} is null or empty. When no security principal exists, current user is treated as
     * {@code Anonymous} (same rules as {@link AgentThing} thread helpers).
     */
    public static void ensureConversationOwnedByCurrentUser(String conversationId, String messagePrefix)
            throws Exception {
        if (conversationId == null || conversationId.isEmpty()) {
            return;
        }
        Thing dataTable = requireDataTable(messagePrefix);
        ValueCollection getParams = new ValueCollection();
        getParams.put("key", new StringPrimitive(conversationId));
        Object rawGet = dataTable.processServiceRequest("GetDataTableEntryByKey", getParams);
        InfoTable existing = (InfoTable) rawGet;
        if (existing == null || existing.getRowCount() == 0) {
            throw new Exception(messagePrefix + ": Conversation not found: " + conversationId
                    + ". Use GetOrCreateConversationId to create or list threads.");
        }
        com.thingworx.types.collections.ValueCollection row =
                (com.thingworx.types.collections.ValueCollection) existing.getRow(0);
        String rowUser = (String) row.getValue("username");
        String currentUser = getCurrentUsername();
        if (rowUser == null || rowUser.isBlank()) {
            throw new Exception(messagePrefix + ": Conversation " + conversationId
                    + " has no valid username on thread row; repair or recreate via GetOrCreateConversationId.");
        }
        if (!rowUser.equals(currentUser)) {
            throw new Exception(messagePrefix + ": Conversation " + conversationId
                    + " does not belong to the current user.");
        }
    }

    /**
     * Loads thread metadata after verifying the row exists and belongs to the current user.
     */
    public static ConversationMetadata loadConversationMetadataForCurrentUser(String conversationId, String messagePrefix)
            throws Exception {
        if (conversationId == null || conversationId.isEmpty()) {
            throw new IllegalArgumentException(messagePrefix + ": conversationId is required.");
        }
        Thing dataTable = requireDataTable(messagePrefix);
        ValueCollection getParams = new ValueCollection();
        getParams.put("key", new StringPrimitive(conversationId));
        Object rawGet = dataTable.processServiceRequest("GetDataTableEntryByKey", getParams);
        InfoTable existing = (InfoTable) rawGet;
        if (existing == null || existing.getRowCount() == 0) {
            throw new Exception(messagePrefix + ": Conversation not found: " + conversationId
                    + ". Use GetOrCreateConversationId to create or list threads.");
        }
        com.thingworx.types.collections.ValueCollection row =
                (com.thingworx.types.collections.ValueCollection) existing.getRow(0);
        String rowUser = (String) row.getValue("username");
        String currentUser = getCurrentUsername();
        if (rowUser == null || rowUser.isBlank()) {
            throw new Exception(messagePrefix + ": Conversation " + conversationId
                    + " has no valid username on thread row; repair or recreate via GetOrCreateConversationId.");
        }
        if (!rowUser.equals(currentUser)) {
            throw new Exception(messagePrefix + ": Conversation " + conversationId
                    + " does not belong to the current user.");
        }
        String agentName = row.getValue("agentName") != null ? String.valueOf(row.getValue("agentName")) : "";
        DateTime clearedAt = parseOptionalDateTime(row.getValue("historyClearedAt"));
        return new ConversationMetadata(conversationId, agentName, clearedAt);
    }

    /**
     * Loads {@code agentName} + {@code historyClearedAt} for an existing thread row without checking caller username
     * (ThingWorx service permissions decide access). Does **not** validate that the caller owns the row — use
     * {@link #loadConversationMetadataForCurrentUser} on the **Gateway** path when the end-user must match the thread
     * owner. Direct {@link AgentThing} services that only need "row exists" use this method.
     */
    public static ConversationMetadata loadConversationMetadata(String conversationId, String messagePrefix)
            throws Exception {
        if (conversationId == null || conversationId.isEmpty()) {
            throw new IllegalArgumentException(messagePrefix + ": conversationId is required.");
        }
        Thing dataTable = requireDataTable(messagePrefix);
        ValueCollection getParams = new ValueCollection();
        getParams.put("key", new StringPrimitive(conversationId));
        Object rawGet = dataTable.processServiceRequest("GetDataTableEntryByKey", getParams);
        InfoTable existing = (InfoTable) rawGet;
        if (existing == null || existing.getRowCount() == 0) {
            throw new Exception(messagePrefix + ": Conversation not found: " + conversationId
                    + ". Use GetOrCreateConversationId to create or list threads.");
        }
        com.thingworx.types.collections.ValueCollection row =
                (com.thingworx.types.collections.ValueCollection) existing.getRow(0);
        String agentName = row.getValue("agentName") != null ? String.valueOf(row.getValue("agentName")) : "";
        DateTime clearedAt = parseOptionalDateTime(row.getValue("historyClearedAt"));
        return new ConversationMetadata(conversationId, agentName, clearedAt);
    }

    /**
     * Read-only {@code historyClearedAt} for {@code conversationId}. No caller-ownership check — for schedulers and
     * lifecycle paths without a user {@link com.thingworx.security.context.SecurityContext}. Returns {@code null} when the table/row/field is absent
     * or on lookup failure.
     */
    public static DateTime loadHistoryClearedAtOrNull(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return null;
        }
        try {
            RootEntity dataTableEntity =
                    PlatformAccess.findProgrammatic(THREAD_DATA_TABLE_NAME, RelationshipTypes.ThingworxRelationshipTypes.Thing);
            if (dataTableEntity == null || !(dataTableEntity instanceof Thing)) {
                return null;
            }
            Thing dataTable = (Thing) dataTableEntity;
            ValueCollection getParams = new ValueCollection();
            getParams.put("key", new StringPrimitive(conversationId));
            Object rawGet = dataTable.processServiceRequest("GetDataTableEntryByKey", getParams);
            InfoTable existing = (InfoTable) rawGet;
            if (existing == null || existing.getRowCount() == 0) {
                return null;
            }
            com.thingworx.types.collections.ValueCollection row =
                    (com.thingworx.types.collections.ValueCollection) existing.getRow(0);
            return parseOptionalDateTime(row.getValue("historyClearedAt"));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Sets {@code historyClearedAt} on the thread row; caller must already hold {@code parlerConversationLock}.
     * Does not enforce end-user ownership — ThingWorx service permissions decide who may call {@code ClearConversation}
     * ({@code docs/agent/conversation-continuity.md} §6). Validates row existence and optional {@code agentName} binding.
     */
    public static void markHistoryCleared(String conversationId, DateTime when, String expectedAgentThingName,
            String messagePrefix) throws Exception {
        if (conversationId == null || conversationId.isEmpty()) {
            throw new Exception(messagePrefix + ": conversationId is required.");
        }
        Thing dataTable = requireDataTable(messagePrefix);
        ValueCollection getParams = new ValueCollection();
        getParams.put("key", new StringPrimitive(conversationId));
        InfoTable existing = (InfoTable) dataTable.processServiceRequest("GetDataTableEntryByKey", getParams);
        if (existing == null || existing.getRowCount() == 0) {
            throw new Exception(messagePrefix + ": Conversation not found: " + conversationId);
        }
        com.thingworx.types.collections.ValueCollection row =
                (com.thingworx.types.collections.ValueCollection) existing.getRow(0);
        String agentName = row.getValue("agentName") != null ? String.valueOf(row.getValue("agentName")).trim() : "";
        MarkHistoryClearedAgentBinding.verifyExpectedAgentThingIfPresent(expectedAgentThingName, agentName, conversationId,
                messagePrefix);
        row.put("historyClearedAt", new DatetimePrimitive(when));
        row.put("updatedAt", new DatetimePrimitive(new DateTime()));
        InfoTable updateIt = new InfoTable(existing.getDataShape());
        updateIt.addRow(row);
        ValueCollection updateParams = new ValueCollection();
        updateParams.SetInfoTableValue("values", updateIt);
        updateParams.put("tags", null);
        updateParams.put("location", null);
        updateParams.put("source", null);
        updateParams.put("sourceType", null);
        dataTable.processServiceRequest("UpdateDataTableEntry", updateParams);
    }

    private static DateTime parseOptionalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof DatetimePrimitive) {
            return ((DatetimePrimitive) value).getValue();
        }
        if (value instanceof DateTime) {
            return (DateTime) value;
        }
        if (value instanceof java.util.Date) {
            return new DateTime(value);
        }
        return null;
    }

    private static Thing requireDataTable(String messagePrefix) throws Exception {
        RootEntity dataTableEntity =
                PlatformAccess.findProgrammatic(THREAD_DATA_TABLE_NAME, RelationshipTypes.ThingworxRelationshipTypes.Thing);
        if (dataTableEntity == null || !(dataTableEntity instanceof Thing)) {
            throw new Exception(messagePrefix + ": " + THREAD_DATA_TABLE_NAME
                    + " not found. Import the extension entities.");
        }
        return (Thing) dataTableEntity;
    }

    private static String getCurrentUsername() {
        try {
            SecurityContext ctx = ThreadLocalContext.getSecurityContext();
            return ctx != null && ctx.getName() != null ? ctx.getName() : "Anonymous";
        } catch (Exception e) {
            return "Anonymous";
        }
    }
}
