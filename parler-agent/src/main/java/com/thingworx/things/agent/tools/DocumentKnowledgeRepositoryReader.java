package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.things.agent.skillregistry.RepositorySkillScanner;
import com.thingworx.types.InfoTable;

/**
 * FileRepository listing and text reads for document-knowledge packages (§7).
 */
public final class DocumentKnowledgeRepositoryReader {

    private DocumentKnowledgeRepositoryReader() {}

    public static List<String> listPackageDirectoryNames(RepositoryReader reader, String rootPath) throws Exception {
        InfoTable listing = reader.getFileListing(DocumentKnowledgePaths.normalizeRoot(rootPath), "");
        List<String> names = RepositorySkillScanner.extractTopLevelDirectoryNames(listing);
        Collections.sort(names);
        return names;
    }

    public static String loadText(RepositoryReader reader, String path) throws Exception {
        return reader.loadText(path);
    }
}
