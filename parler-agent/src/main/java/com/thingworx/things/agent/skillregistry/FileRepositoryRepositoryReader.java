package com.thingworx.things.agent.skillregistry;

import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.things.repository.FileRepositoryThing;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * {@link RepositoryReader} backed by a live {@link FileRepositoryThing}. The FileRepository services authorize every
 * call themselves; the two factories only choose the platform entry.
 */
public final class FileRepositoryRepositoryReader {

    @FunctionalInterface
    interface ServiceEntry {
        InfoTable invoke(FileRepositoryThing repo, String service, ValueCollection params) throws Exception;
    }

    private FileRepositoryRepositoryReader() {}

    /**
     * Agent configuration loads (skills registry, extended tools, taxonomy, policies, playbooks): the current user or
     * the System user needs ServiceInvoke ({@link PlatformAccess#invokeProgrammatic}).
     */
    public static RepositoryReader forConfiguration(FileRepositoryThing repo) {
        return over(repo, PlatformAccess::invokeProgrammatic);
    }

    /**
     * Reads a tool makes on the user's behalf (document knowledge, skill bodies): the current user needs ServiceInvoke
     * ({@link PlatformAccess#invokeAsUser}).
     */
    public static RepositoryReader forCurrentUser(FileRepositoryThing repo) {
        return over(repo, PlatformAccess::invokeAsUser);
    }

    private static RepositoryReader over(FileRepositoryThing repo, ServiceEntry entry) {
        return new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) throws Exception {
                ValueCollection vc = new ValueCollection();
                vc.put("path", new StringPrimitive(path != null ? path : "/"));
                return entry.invoke(repo, "BrowseDirectory", vc);
            }

            @Override
            public String loadText(String path) throws Exception {
                ValueCollection vc = new ValueCollection();
                vc.put("path", new StringPrimitive(path));
                InfoTable it = entry.invoke(repo, "LoadText", vc);
                if (it == null || it.getRowCount() == 0) {
                    return "";
                }
                Object content = it.getRow(0).getValue("Content");
                if (content == null) {
                    content = it.getRow(0).getValue("result");
                }
                return content != null ? content.toString() : "";
            }

            @Override
            public byte[] loadBinary(String path) throws Exception {
                ValueCollection vc = new ValueCollection();
                vc.put("path", new StringPrimitive(path));
                return FileRepositoryLoadBinarySupport.unwrap(entry.invoke(repo, "LoadBinary", vc));
            }
        };
    }
}
