package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Built-in default document-set resolver — the conservative high-confidence matcher
 * (docs/operations/knowledge-retrieval-pipeline.md §3.3).
 *
 * <p>Joins the bound Thing's asset identity against each document's
 * {@code documentProfile.assetModels[]} plus the manifest-root {@code assetModels[]}
 * fallback (the M1 join key, same fields {@link DocumentKnowledgeSearchScorer} reads),
 * by <em>normalized exact equality</em>. It scopes only on a high-confidence exact
 * match and returns {@code default-empty} on no/ambiguous match, falling through the
 * retrieval ladder rather than mis-scoping.
 *
 * <p>This is generic mechanism: an exact join on App-developer-curated metadata. It
 * hard-codes no customer mapping and invents no domain knowledge (trade-off §1.2).
 * Exact equality (not the scorer's substring matching) is what makes it
 * high-confidence: a partial or fuzzy identity falls through to {@code default-empty}.
 */
public final class DocumentSetResolver {

    private DocumentSetResolver() {
    }

    /**
     * Resolves the built-in default document set for the given asset identity.
     *
     * @param assetIdentity the bound Thing's asset-model identifier(s) from host-context
     * @param documents     all candidate document manifests
     * @return {@link ResolverSource#DEFAULT_MATCH} with the matching docIds when at
     *         least one document's assetModels exactly matches an identity value;
     *         {@link ResolverSource#DEFAULT_EMPTY} otherwise
     */
    public static ResolvedDocumentSet resolveDefault(
            List<String> assetIdentity,
            Iterable<DocumentKnowledgePackageManifest> documents) {
        Set<String> identity = normalizedSet(assetIdentity);
        if (identity.isEmpty() || documents == null) {
            return new ResolvedDocumentSet(List.of(), ResolverSource.DEFAULT_EMPTY);
        }
        List<String> matched = new ArrayList<>();
        for (DocumentKnowledgePackageManifest manifest : documents) {
            if (manifest == null || manifest.docId() == null) {
                continue;
            }
            if (matchesIdentity(manifest, identity)) {
                matched.add(manifest.docId());
            }
        }
        return matched.isEmpty()
                ? new ResolvedDocumentSet(List.of(), ResolverSource.DEFAULT_EMPTY)
                : new ResolvedDocumentSet(matched, ResolverSource.DEFAULT_MATCH);
    }

    private static boolean matchesIdentity(
            DocumentKnowledgePackageManifest manifest, Set<String> identity) {
        for (String model : manifest.assetModels()) {
            if (identity.contains(normalize(model))) {
                return true;
            }
        }
        for (String model : manifest.documentProfile().assetModels()) {
            if (identity.contains(normalize(model))) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> normalizedSet(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        if (values != null) {
            for (String v : values) {
                String n = normalize(v);
                if (!n.isEmpty()) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    /** Normalized exact-match key: trim, collapse internal whitespace, lowercase. */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
