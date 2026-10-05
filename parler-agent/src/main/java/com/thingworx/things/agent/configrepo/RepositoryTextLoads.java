package com.thingworx.things.agent.configrepo;

import com.thingworx.things.agent.skillregistry.RepositoryReader;

/**
 * Normalizes FileRepository {@link RepositoryReader#loadText} outcomes into missing vs read failure for
 * configuration-repository paths (see {@code docs/agent/configuration-repository.md}).
 */
public final class RepositoryTextLoads {

    public enum Kind {
        /** Path or object not present — no ERROR log expected for v1 absence semantics. */
        MISSING,
        /** Present but zero-length after trim (distinct from missing only in diagnostics). */
        EMPTY,
        /** Non-blank content. */
        CONTENT,
        /** Load failed for a reason other than "missing". */
        READ_ERROR
    }

    public static final class Result {
        private final Kind kind;
        private final String text;
        private final String errorMessage;

        Result(Kind kind, String text, String errorMessage) {
            this.kind = kind;
            this.text = text;
            this.errorMessage = errorMessage;
        }

        public Kind kind() {
            return kind;
        }

        /** Non-null only when {@link #kind()} is {@link Kind#CONTENT}. */
        public String text() {
            return text;
        }

        /** Non-null only when {@link #kind()} is {@link Kind#READ_ERROR}. */
        public String errorMessage() {
            return errorMessage;
        }
    }

    private RepositoryTextLoads() {}

    public static Result loadText(RepositoryReader reader, String path) {
        if (reader == null || path == null || path.isBlank()) {
            return new Result(Kind.MISSING, null, null);
        }
        try {
            String raw = reader.loadText(path);
            if (raw == null) {
                return new Result(Kind.MISSING, null, null);
            }
            String t = raw.trim();
            if (t.isEmpty()) {
                return new Result(Kind.EMPTY, "", null);
            }
            return new Result(Kind.CONTENT, raw, null);
        } catch (Exception e) {
            if (isProbablyMissingFile(e)) {
                return new Result(Kind.MISSING, null, null);
            }
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new Result(Kind.READ_ERROR, null, msg);
        }
    }

    /**
     * Best-effort classification of ThingWorx FileRepository "path not found" failures (exact message varies by
     * platform build).
     */
    public static boolean isProbablyMissingFile(Throwable e) {
        if (e == null) {
            return false;
        }
        String m = (e.getMessage() != null ? e.getMessage() : "").toLowerCase();
        if (m.isEmpty()) {
            Throwable c = e.getCause();
            if (c != null) {
                m = (c.getMessage() != null ? c.getMessage() : "").toLowerCase();
            }
        }
        return m.contains("not found")
                || m.contains("does not exist")
                || m.contains("unable to find")
                || m.contains("cannot find")
                || m.contains("no such file")
                || m.contains("unknown path")
                || m.contains("404");
    }
}
