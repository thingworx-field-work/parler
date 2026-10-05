package com.thingworx.things.agent.llm.ratecontrol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.apache.http.message.BasicHeader;
import org.junit.jupiter.api.Test;

class RateLimitHeaderParserTest {

    @Test
    void blockedUntil_emptyHeaders_usesDefault60s() {
        long before = System.currentTimeMillis();
        long until = RateLimitHeaderParser.blockedUntilEpochMs(null);
        long after = System.currentTimeMillis();
        assertTrue(until >= before + 60_000);
        assertTrue(until <= after + 60_000);
    }

    @Test
    void blockedUntil_retryAfterSeconds_honorsUpstreamValue() {
        BasicHeader[] headers = { new BasicHeader("Retry-After", "5") };
        long before = System.currentTimeMillis();
        long until = RateLimitHeaderParser.blockedUntilEpochMs(headers);
        long after = System.currentTimeMillis();
        assertTrue(until >= before + 5_000);
        assertTrue(until <= after + 5_000);
        assertTrue(until < before + 60_000, "must not apply 60s floor over shorter Retry-After");
    }

    @Test
    void blockedUntil_retryAfterIsoDate_parsesFutureInstant() {
        Instant future = Instant.now().plusSeconds(12);
        BasicHeader[] headers = { new BasicHeader("Retry-After", future.toString()) };
        long until = RateLimitHeaderParser.blockedUntilEpochMs(headers);
        assertTrue(until >= future.toEpochMilli() - 1000);
        assertTrue(until <= future.toEpochMilli() + 2000);
    }

    @Test
    void blockedUntil_resetHeaderOnly_usesResetEpoch() {
        BasicHeader[] headers = {
            new BasicHeader("x-ratelimit-reset-tokens", "10")
        };
        long before = System.currentTimeMillis();
        long until = RateLimitHeaderParser.blockedUntilEpochMs(headers);
        long after = System.currentTimeMillis();
        assertTrue(until >= before + 10_000);
        assertTrue(until <= after + 10_000);
    }
}
