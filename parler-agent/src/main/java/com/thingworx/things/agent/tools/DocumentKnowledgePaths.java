package com.thingworx.things.agent.tools;

/**
 * FileRepository-relative path helpers for document-knowledge packages.
 */
public final class DocumentKnowledgePaths {

    private DocumentKnowledgePaths() {}

    public static String normalizeRoot(String root) {
        if (root == null || root.isBlank()) {
            return "/document-knowledge";
        }
        String r = root.trim().replace('\\', '/');
        if (!r.startsWith("/")) {
            r = "/" + r;
        }
        while (r.length() > 1 && r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    public static String join(String base, String child) {
        String b = normalizeRoot(base);
        if (child == null || child.isBlank()) {
            return b;
        }
        String c = child.trim().replace('\\', '/');
        if (c.startsWith("/")) {
            c = c.substring(1);
        }
        return b + "/" + c;
    }

    public static String chunkKey(String docId, String chunkId) {
        return docId + "\u0000" + chunkId;
    }
}
