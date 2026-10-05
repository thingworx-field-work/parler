package com.thingworx.things.agent.transform.time;

/** Bucket boundary kind for G3 assignment. */
public enum BucketKind {
    /** Fixed elapsed UTC duration from an explicit anchor. */
    FIXED_DURATION,
    /** Calendar day boundaries in a declared IANA zone. */
    CALENDAR_DAY,
    /** Calendar hour boundaries in a declared IANA zone. */
    CALENDAR_HOUR
}
