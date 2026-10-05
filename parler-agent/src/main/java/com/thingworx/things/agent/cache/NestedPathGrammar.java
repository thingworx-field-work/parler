package com.thingworx.things.agent.cache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Bounded cell-path grammar for nested INFOTABLE / JSON drill-in (U2 M2 / BP7).
 *
 * <p>Accepted form: dotted keys with explicit {@code [index]} segments only, e.g.
 * {@code [0].children[1].detail}. Rejects {@code ..}, {@code /}, {@code \}, glob, recursive
 * descent, and expressions. No Playbook {@code PlaybookJsonRowPath} extension (deferred
 * {@code playbook-nested-consumption}).
 */
public final class NestedPathGrammar {

    public static final int MAX_PATH_CHARS = 512;
    public static final int MAX_SEGMENTS = 16;
    public static final int MAX_INDEX = 100_000;

    public enum SegmentKind {
        FIELD,
        INDEX
    }

    public static final class Segment {
        private final SegmentKind kind;
        private final String fieldName;
        private final int index;

        private Segment(SegmentKind kind, String fieldName, int index) {
            this.kind = kind;
            this.fieldName = fieldName;
            this.index = index;
        }

        public static Segment field(String name) {
            return new Segment(SegmentKind.FIELD, Objects.requireNonNull(name, "name"), -1);
        }

        public static Segment index(int index) {
            return new Segment(SegmentKind.INDEX, null, index);
        }

        public SegmentKind kind() {
            return kind;
        }

        public String fieldName() {
            return fieldName;
        }

        public int index() {
            return index;
        }
    }

    public static final class ParseException extends Exception {
        private final String code;

        public ParseException(String code, String message) {
            super(message);
            this.code = code == null ? "INVALID_PATH" : code;
        }

        public String code() {
            return code;
        }
    }

    private NestedPathGrammar() {}

    public static List<Segment> parse(String path) throws ParseException {
        if (path == null || path.isBlank()) {
            throw new ParseException("INVALID_PATH", "path is required");
        }
        String p = path.trim();
        if (p.length() > MAX_PATH_CHARS) {
            throw new ParseException("PATH_TOO_LONG",
                    "path exceeds " + MAX_PATH_CHARS + " characters");
        }
        if (p.contains("..") || p.indexOf('/') >= 0 || p.indexOf('\\') >= 0
                || p.indexOf('*') >= 0 || p.indexOf('?') >= 0 || p.indexOf('$') >= 0
                || p.contains("**")) {
            throw new ParseException("INVALID_PATH",
                    "path rejects .. / \\ glob recursive-descent and expressions");
        }
        List<Segment> out = new ArrayList<>();
        int i = 0;
        while (i < p.length()) {
            if (out.size() >= MAX_SEGMENTS) {
                throw new ParseException("PATH_TOO_DEEP",
                        "path exceeds " + MAX_SEGMENTS + " segments");
            }
            char c = p.charAt(i);
            if (c == '[') {
                int close = p.indexOf(']', i + 1);
                if (close < 0) {
                    throw new ParseException("INVALID_PATH", "unclosed [index]");
                }
                String idxText = p.substring(i + 1, close).trim();
                if (idxText.isEmpty() || !idxText.chars().allMatch(Character::isDigit)) {
                    throw new ParseException("INVALID_PATH", "index must be a non-negative integer");
                }
                long idxLong;
                try {
                    idxLong = Long.parseLong(idxText);
                } catch (NumberFormatException e) {
                    throw new ParseException("INVALID_PATH", "index out of range");
                }
                if (idxLong > MAX_INDEX) {
                    throw new ParseException("INDEX_TOO_LARGE",
                            "index exceeds " + MAX_INDEX);
                }
                out.add(Segment.index((int) idxLong));
                i = close + 1;
                if (i < p.length() && p.charAt(i) == '.') {
                    i++;
                }
                continue;
            }
            if (c == '.') {
                throw new ParseException("INVALID_PATH", "unexpected '.'");
            }
            int start = i;
            while (i < p.length()) {
                char ch = p.charAt(i);
                if (ch == '.' || ch == '[') {
                    break;
                }
                i++;
            }
            String name = p.substring(start, i);
            if (name.isEmpty()) {
                throw new ParseException("INVALID_PATH", "empty field name");
            }
            if (!isFieldName(name)) {
                throw new ParseException("INVALID_PATH", "invalid field name: " + name);
            }
            out.add(Segment.field(name));
            if (i < p.length() && p.charAt(i) == '.') {
                i++;
                if (i >= p.length()) {
                    throw new ParseException("INVALID_PATH", "trailing '.'");
                }
            }
        }
        if (out.isEmpty()) {
            throw new ParseException("INVALID_PATH", "path produced no segments");
        }
        return Collections.unmodifiableList(out);
    }

    private static boolean isFieldName(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-')) {
                return false;
            }
        }
        return true;
    }
}
