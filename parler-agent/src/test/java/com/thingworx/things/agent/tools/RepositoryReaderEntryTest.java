package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.skillregistry.FileRepositoryRepositoryReader;

/**
 * Tool reads of a FileRepository go through the current user's API entry; configuration loads through the
 * programmatic one. With no live repository, the entry shows up in the stack of the call that reached it.
 */
class RepositoryReaderEntryTest {

    @Test
    void currentUserRepositoryReaderUsesTheApiEntry() {
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> FileRepositoryRepositoryReader.forCurrentUser(null).loadText("/docs/a.md"));
        assertTrue(Arrays.stream(e.getStackTrace()).anyMatch(f -> "invokeAsUser".equals(f.getMethodName())));
    }

    @Test
    void configurationRepositoryReaderUsesTheProgrammaticEntry() {
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> FileRepositoryRepositoryReader.forConfiguration(null).loadText("/tools/extended_tools.json"));
        assertTrue(Arrays.stream(e.getStackTrace()).anyMatch(f -> "invokeProgrammatic".equals(f.getMethodName())));
    }

}
