package com.thingworx.things.agent.configrepo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.thingworx.things.agent.llm.ToolDefinition;

/** Immutable registry of file-backed extended tools for one prompt-context snapshot. */
public final class ExtendedToolRegistrySnapshot {

    private static final ExtendedToolRegistrySnapshot MISSING =
            new ExtendedToolRegistrySnapshot(Collections.emptyMap(), false, true);
    private static final ExtendedToolRegistrySnapshot INVALID =
            new ExtendedToolRegistrySnapshot(Collections.emptyMap(), true, false);

    private final Map<String, ExtendedToolDefinition> byName;
    private final boolean fileInvalid;
    private final boolean fileMissing;

    private ExtendedToolRegistrySnapshot(Map<String, ExtendedToolDefinition> byName, boolean fileInvalid,
            boolean fileMissing) {
        this.byName = byName;
        this.fileInvalid = fileInvalid;
        this.fileMissing = fileMissing;
    }

    public static ExtendedToolRegistrySnapshot missing() {
        return MISSING;
    }

    public static ExtendedToolRegistrySnapshot invalid() {
        return INVALID;
    }

    public static ExtendedToolRegistrySnapshot ok(List<ExtendedToolDefinition> tools) {
        Map<String, ExtendedToolDefinition> m = new HashMap<>();
        for (ExtendedToolDefinition d : tools) {
            m.put(d.llmName(), d);
        }
        return new ExtendedToolRegistrySnapshot(Collections.unmodifiableMap(m), false, false);
    }

    public boolean isFileInvalid() {
        return fileInvalid;
    }

    public boolean isFileMissing() {
        return fileMissing;
    }

    public Optional<ExtendedToolDefinition> find(String llmFunctionName) {
        if (llmFunctionName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName.get(llmFunctionName));
    }

    /**
     * When {@code (resolvedThingName, platformServiceName)} matches an extended tool's
     * {@link ExtendedToolDefinition#resolvedTargetThingName()} and {@link ExtendedToolDefinition#serviceName()},
     * returns one definition (lexicographically smallest {@link ExtendedToolDefinition#llmName()} if multiple).
     */
    public Optional<ExtendedToolDefinition> findByTargetService(String resolvedThingName,
            String platformServiceName) {
        if (resolvedThingName == null || resolvedThingName.isEmpty()
                || platformServiceName == null || platformServiceName.isEmpty()) {
            return Optional.empty();
        }
        if (fileInvalid || fileMissing || byName.isEmpty()) {
            return Optional.empty();
        }
        List<ExtendedToolDefinition> matches = new ArrayList<>();
        for (ExtendedToolDefinition d : byName.values()) {
            if (d == null) {
                continue;
            }
            if (resolvedThingName.equals(d.resolvedTargetThingName())
                    && platformServiceName.equals(d.serviceName())) {
                matches.add(d);
            }
        }
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        matches.sort(Comparator.comparing(ExtendedToolDefinition::llmName, Comparator.nullsLast(String::compareTo)));
        return Optional.of(matches.get(0));
    }

    public Map<String, ExtendedToolDefinition> allByName() {
        return byName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ExtendedToolRegistrySnapshot)) {
            return false;
        }
        ExtendedToolRegistrySnapshot that = (ExtendedToolRegistrySnapshot) o;
        return fileInvalid == that.fileInvalid && fileMissing == that.fileMissing && Objects.equals(byName, that.byName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(byName, fileInvalid, fileMissing);
    }
}
