package com.thingworx.things.agent.taxonomy;

import java.util.Objects;

/** One {@code identityProperties} entry from v3 {@code identity-types.json}. */
public final class ThingIdentityPropertyMatchV3 {

    private final String name;
    private final String match;

    public ThingIdentityPropertyMatchV3(String name, String match) {
        this.name = name != null ? name.trim() : "";
        this.match = match != null ? match.trim() : "";
    }

    public String name() {
        return name;
    }

    public String match() {
        return match;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ThingIdentityPropertyMatchV3)) {
            return false;
        }
        ThingIdentityPropertyMatchV3 that = (ThingIdentityPropertyMatchV3) o;
        return Objects.equals(name, that.name) && Objects.equals(match, that.match);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, match);
    }
}
