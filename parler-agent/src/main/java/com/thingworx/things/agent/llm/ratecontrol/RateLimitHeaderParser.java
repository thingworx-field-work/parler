package com.thingworx.things.agent.llm.ratecontrol;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;

import org.apache.http.Header;

import com.thingworx.things.agent.llm.LlmHttpDiagnostics;

/**
 * Parses safe rate-limit headers into {@code blockedUntilEpochMs} ({@code docs/agent/rate-control.md} §9).
 */
final class RateLimitHeaderParser {

    private static final long DEFAULT_BLOCK_MS = 60_000L;

    private RateLimitHeaderParser() {}

    static long blockedUntilEpochMs(Header[] headers) {
        long now = System.currentTimeMillis();
        if (headers == null || headers.length == 0) {
            return now + DEFAULT_BLOCK_MS;
        }
        Long retryAfter = parseRetryAfterSeconds(headers);
        if (retryAfter != null) {
            return now + retryAfter * 1000L;
        }
        long fromResets = parseResetHeaders(headers, now);
        if (fromResets > now) {
            return fromResets;
        }
        return now + DEFAULT_BLOCK_MS;
    }

    static String formatSafeHeaderSnapshot(Map<String, String> safeHeaders) {
        if (safeHeaders == null || safeHeaders.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : safeHeaders.entrySet()) {
            if (!first) {
                sb.append("; ");
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue() != null ? e.getValue() : "");
        }
        return sb.toString();
    }

    static Map<String, String> safeHeadersFromHttp(Header[] headers) {
        return LlmHttpDiagnostics.collectSafeHeaders(headers);
    }

    private static Long parseRetryAfterSeconds(Header[] headers) {
        for (Header h : headers) {
            if (h == null || h.getName() == null) {
                continue;
            }
            if (!"retry-after".equalsIgnoreCase(h.getName())) {
                continue;
            }
            String v = h.getValue();
            if (v == null || v.isBlank()) {
                continue;
            }
            try {
                double sec = Double.parseDouble(v.trim());
                if (sec > 0) {
                    return (long) Math.ceil(sec);
                }
            } catch (NumberFormatException ignored) {
                try {
                    Instant instant = Instant.parse(v.trim());
                    long deltaMs = instant.toEpochMilli() - System.currentTimeMillis();
                    if (deltaMs > 0) {
                        return (deltaMs + 999) / 1000;
                    }
                } catch (DateTimeParseException ignored2) {
                    // fall through
                }
            }
        }
        return null;
    }

    private static long parseResetHeaders(Header[] headers, long nowMs) {
        long best = 0;
        for (Header h : headers) {
            if (h == null || h.getName() == null || h.getValue() == null) {
                continue;
            }
            String name = h.getName().toLowerCase(Locale.ROOT);
            if (!isResetHeaderName(name)) {
                continue;
            }
            long candidate = parseResetValue(h.getValue(), nowMs);
            if (candidate > best) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean isResetHeaderName(String nameLower) {
        return nameLower.equals("anthropic-ratelimit-input-tokens-reset")
                || nameLower.equals("anthropic-ratelimit-requests-reset")
                || nameLower.equals("x-ratelimit-reset-tokens")
                || nameLower.equals("x-ratelimit-reset-requests");
    }

    private static long parseResetValue(String raw, long nowMs) {
        String v = raw.trim();
        if (v.isEmpty()) {
            return 0;
        }
        try {
            if (v.chars().allMatch(Character::isDigit)) {
                long sec = Long.parseLong(v);
                return nowMs + sec * 1000L;
            }
            Instant instant = Instant.parse(v);
            return instant.toEpochMilli();
        } catch (NumberFormatException | DateTimeParseException e) {
            try {
                double sec = Double.parseDouble(v);
                if (sec > 0) {
                    return nowMs + (long) (sec * 1000.0);
                }
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }
        return 0;
    }
}
