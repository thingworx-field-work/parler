package com.thingworx.things.agent.tools;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Builds mashup-relative FileRepository links aligned with table CSV export
 * ({@code parler-ui/parler-ui.js} {@code thingworxFileRepositoriesHref} /
 * {@code thingworxFileRepositoryDownloaderHref}).
 */
public final class DocumentKnowledgeLinkBuilder {

    private DocumentKnowledgeLinkBuilder() {}

    public static boolean fileRepoPathNeedsDownloaderQuery(String repositoryRelativePath) {
        if (repositoryRelativePath == null) {
            return false;
        }
        return repositoryRelativePath.indexOf('?') >= 0 || repositoryRelativePath.indexOf('#') >= 0;
    }

    /**
     * @param repository FileRepository Thing name
     * @param repositoryRelativePath path as stored in manifest / table exportFile
     * @param page PDF page number; omitted from href when null, zero, or negative
     */
    public static String buildPdfHref(String repository, String repositoryRelativePath, Integer page) {
        String repo = trimToNull(repository);
        String rel = normalizeSlashes(trimToNull(repositoryRelativePath));
        if (repo == null || rel == null) {
            return "";
        }
        String base = fileRepoPathNeedsDownloaderQuery(rel)
                ? buildDownloaderHref(repo, rel)
                : buildPathStyleHref(repo, rel);
        return appendPageFragment(base, page);
    }

    static String buildPathStyleHref(String repository, String repositoryRelativePath) {
        String path = repositoryRelativePath.startsWith("/") ? repositoryRelativePath : "/" + repositoryRelativePath;
        return "/Thingworx/FileRepositories/" + encodeURIComponent(repository) + encodeUri(path);
    }

    static String buildDownloaderHref(String repository, String repositoryRelativePath) {
        String rel = repositoryRelativePath.startsWith("/")
                ? repositoryRelativePath.substring(1)
                : repositoryRelativePath;
        String q = "download-repository=" + urlFormEncode(repository) + "&download-path=" + urlFormEncode(rel);
        return "/Thingworx/FileRepositoryDownloader?" + q;
    }

    static String appendPageFragment(String href, Integer page) {
        if (page == null || page <= 0) {
            return href;
        }
        return href + "#page=" + page;
    }

    private static String normalizeSlashes(String path) {
        return path == null ? null : path.replace('\\', '/');
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    /** JavaScript {@code encodeURIComponent} equivalent for repository Thing names. */
    static String encodeURIComponent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%21", "!")
                .replace("%27", "'")
                .replace("%28", "(")
                .replace("%29", ")")
                .replace("%7E", "~");
    }

    /** JavaScript {@code encodeURI} equivalent for repository-relative paths (UTF-8 byte encoding). */
    static String encodeUri(String path) {
        StringBuilder result = new StringBuilder(path.length() * 3);
        for (int i = 0; i < path.length(); ) {
            int cp = path.codePointAt(i);
            if (cp <= 0xFFFF && isEncodeUriUnescaped((char) cp)) {
                result.append((char) cp);
            } else {
                byte[] utf8 = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                for (byte b : utf8) {
                    result.append(String.format("%%%02X", b & 0xFF));
                }
            }
            i += Character.charCount(cp);
        }
        return result.toString();
    }

    private static boolean isEncodeUriUnescaped(char ch) {
        return (ch >= 'A' && ch <= 'Z')
                || (ch >= 'a' && ch <= 'z')
                || (ch >= '0' && ch <= '9')
                || ";,/?:@&=+$-_.!~*'()#".indexOf(ch) >= 0;
    }

    /** {@code URLSearchParams} / application/x-www-form-urlencoded (space as {@code +}). */
    private static String urlFormEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
