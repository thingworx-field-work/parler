package com.thingworx.things.agent.cache;

import com.thingworx.types.BaseTypes;

/**
 * Walks a required resolved typed schema/proof and rejects {@link BaseTypes#PASSWORD} before file
 * creation. Enforces nestingLevel and maxNodes bounds from the opaque-kernel design.
 */
public final class PasswordSchemaPreflight {

    static final int MAX_DEPTH = 32;
    static final int MAX_NODES = 4096;

    private PasswordSchemaPreflight() {}

    /**
     * @throws ArtifactCacheException {@link ArtifactCacheFaultCode#PASSWORD_REJECTED} or
     *         {@link ArtifactCacheFaultCode#INVALID_REQUEST} for missing/untyped/over-bound proofs
     */
    public static void rejectIfPasswordPresent(ArtifactSchemaNode root) throws ArtifactCacheException {
        if (root == null) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.INVALID_REQUEST,
                    "ArtifactCreateRequest requires a resolved typed PASSWORD proof");
        }
        if (root.isUntypedBytes()) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.PASSWORD_REJECTED,
                    "Untyped bytes cannot prove absence of PASSWORD");
        }
        int[] nodes = new int[1];
        int rootLevel = root.baseType() == BaseTypes.INFOTABLE ? 1 : 0;
        walk(root, rootLevel, nodes);
    }

    private static void walk(ArtifactSchemaNode node, int nestingLevel, int[] nodes)
            throws ArtifactCacheException {
        nodes[0]++;
        if (nodes[0] > MAX_NODES) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.INVALID_REQUEST,
                    "Schema visit count exceeds maxNodes");
        }
        if (nestingLevel > MAX_DEPTH) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.INVALID_REQUEST,
                    "Schema nestingLevel exceeds maxDepth");
        }
        if (node.baseType() == BaseTypes.PASSWORD) {
            throw new ArtifactCacheException(ArtifactCacheFaultCode.PASSWORD_REJECTED,
                    "PASSWORD BaseType is not permitted in artifact payloads");
        }
        for (ArtifactSchemaNode child : node.children()) {
            int childLevel = child.baseType() == BaseTypes.INFOTABLE ? nestingLevel + 1 : nestingLevel;
            walk(child, childLevel, nodes);
        }
    }
}
