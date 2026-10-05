package com.thingworx.things.agent;

import java.util.concurrent.ThreadLocalRandom;

import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;

/**
 * CSV export path collision checks without ThingWorx application logging static init — safe for bare JUnit
 * (see {@code docs/ui/turn-actions-and-table-export.md}).
 */
public final class ParlerTableCsvPathCollisions {

    /** Retries with new random suffix when default path already exists (see {@code docs/ui/table-view-solution.md} §5.2). */
    private static final int MAX_PATH_COLLISION_RETRIES = 8;

    /**
     * Indirection for {@code GetFileListing} so collision logic can be unit-tested without mocking {@link com.thingworx.things.Thing}.
     */
    @FunctionalInterface
    public interface FileListingLookup {
        InfoTable getFileListing(String parentPath, String nameMask) throws Exception;
    }

    private ParlerTableCsvPathCollisions() {}

    /**
     * Same guard as production CSV size skip: Java {@link String#length()} (UTF-16 code units) vs configured cap.
     */
    public static boolean csvExceedsMaxChars(String csv, int maxCsvChars) {
        return csv != null && maxCsvChars >= 0 && csv.length() > maxCsvChars;
    }

    /** TW often throws when the parent folder does not exist yet; treat as empty listing for collision checks. */
    public static boolean isMissingParentDirectoryMessage(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String m = message;
        return m.contains("Directory does not exist") || m.contains("does not exist: /");
    }

    /**
     * Picks a repository-relative path for {@code SaveText}: uses {@code preferredPath} when absent, otherwise
     * {@code stem + "_" + randomHex + ".csv"} up to {@link #MAX_PATH_COLLISION_RETRIES} attempts.
     *
     * @return chosen path, or {@code null} when all candidates appear occupied
     */
    public static String resolveUniqueCsvPath(FileListingLookup lookup, String preferredPath) throws Exception {
        if (preferredPath == null || preferredPath.isEmpty()) {
            return null;
        }
        if (!repositoryFileExists(lookup, preferredPath)) {
            return preferredPath;
        }
        int dot = preferredPath.lastIndexOf('.');
        String stem = dot > 0 ? preferredPath.substring(0, dot) : preferredPath;
        String ext = dot > 0 ? preferredPath.substring(dot) : ".csv";
        for (int i = 0; i < MAX_PATH_COLLISION_RETRIES; i++) {
            String suffix = Long.toHexString(ThreadLocalRandom.current().nextLong());
            if (suffix.length() > 16) {
                suffix = suffix.substring(suffix.length() - 16);
            }
            while (suffix.length() < 8) {
                suffix = "0" + suffix;
            }
            String candidate = stem + "_" + suffix + ext;
            if (!repositoryFileExists(lookup, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * {@code true} when {@code GetFileListing} on the parent directory lists a row whose {@code name} equals the file
     * segment of {@code repositoryRelativeFilePath}.
     */
    public static boolean repositoryFileExists(FileListingLookup lookup, String repositoryRelativeFilePath)
            throws Exception {
        if (lookup == null || repositoryRelativeFilePath == null || repositoryRelativeFilePath.isEmpty()) {
            return false;
        }
        int lastSlash = repositoryRelativeFilePath.lastIndexOf('/');
        if (lastSlash < 0) {
            return false;
        }
        String parent = repositoryRelativeFilePath.substring(0, lastSlash + 1);
        if (parent.isEmpty()) {
            parent = "/";
        }
        String fileName = repositoryRelativeFilePath.substring(lastSlash + 1);
        if (fileName.isEmpty()) {
            return false;
        }
        InfoTable it;
        try {
            it = lookup.getFileListing(parent, "");
        } catch (Exception e) {
            if (isMissingParentDirectoryMessage(e.getMessage())) {
                return false;
            }
            throw e;
        }
        if (it == null) {
            return false;
        }
        for (int r = 0; r < it.getRowCount(); r++) {
            ValueCollection row = it.getRow(r);
            if (row == null) {
                continue;
            }
            String nm = primitiveString(row.getValue("name"));
            if (fileName.equals(nm)) {
                return true;
            }
        }
        return false;
    }

    private static String primitiveString(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof com.thingworx.types.primitives.IPrimitiveType) {
            try {
                Object inner = ((com.thingworx.types.primitives.IPrimitiveType) v).getValue();
                return inner != null ? String.valueOf(inner) : "";
            } catch (Exception ignored) {
                return "";
            }
        }
        return String.valueOf(v);
    }
}
