package com.thingworx.things.agent.llm;

import org.slf4j.Logger;

/** Provider-client warning seam for content-free suffix-classification diagnostics. */
final class SuffixClassificationWarningLogger {

    private SuffixClassificationWarningLogger() {}

    static void log(Logger log, String provider, SuffixClassificationDiagnostics diagnostics) {
        for (SuffixClassificationDiagnostics.Entry entry : diagnostics.getEntries()) {
            log.warn("LLM_SUFFIX_CLASSIFICATION_UNKNOWN provider={} plannedIndex={} role={} charCount={} contentDigest={}",
                    provider,
                    entry.getPlannedIndex(),
                    entry.getRole(),
                    entry.getCharCount(),
                    entry.getContentDigest());
        }
    }
}
