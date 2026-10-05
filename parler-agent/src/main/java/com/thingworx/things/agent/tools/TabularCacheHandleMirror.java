package com.thingworx.things.agent.tools;

import com.thingworx.things.agent.cache.TabularArtifactHub;

/**
 * Sole TOKEN / last-qualifying-handle mutation facade (U2 BP9). All record / summarize-update /
 * inline-clear / miss-prune / conversation-clear / terminal scope clear / restart paths delegate
 * here. Hooks and {@link AgentToolContext} must not keep independent mirrors.
 */
public final class TabularCacheHandleMirror {

    private TabularCacheHandleMirror() {}

    public static boolean isToken(String cacheId) {
        return CachedTabularLastCacheHandle.isToken(cacheId);
    }

    public static String tokenLiteral() {
        return CachedTabularLastCacheHandle.TOKEN;
    }

    public static void recordQualifyingCacheId(String cacheId) {
        AgentToolContext.noteLastQualifyingTabularCacheIdForConversation(cacheId);
    }

    public static void noteFromSummarizeCachedResultJson(String jsonBody) {
        AgentToolContext.noteTokenMirrorFromSummarizeCachedResultJson(jsonBody);
    }

    public static void clearInline() {
        AgentToolContext.clearLastQualifyingTabularCacheIdForConversation();
    }

    public static String resolveConversationMirror() {
        return AgentToolContext.getConversationLastQualifyingTabularCacheId();
    }

    public static void pruneIfPointsTo(String resolvedReadCacheId) {
        AgentToolContext.pruneTabularTokenMirrorIfPointsTo(resolvedReadCacheId);
    }

    /**
     * Conversation clear / history cutoff: drop TOKEN mirror and invalidate that conversation's
     * ArtifactCache scope so closed scopes cannot keep resolving handles (BP9).
     */
    public static void clearForConversationId(String conversationId) {
        AgentToolContext.removeLastQualifyingTabularCacheMirrorForConversationId(conversationId);
        TabularArtifactHub.invalidateScopeForConversation(conversationId);
    }

    /**
     * Terminal REQUEST/HEADLESS completion: drop TOKEN mirror and invalidate the current
     * ArtifactCache scope so closed scopes cannot keep resolving handles.
     */
    public static void clearTerminalScopeAndToken() {
        clearInline();
        TabularArtifactHub.invalidateCurrentScope();
    }
}
