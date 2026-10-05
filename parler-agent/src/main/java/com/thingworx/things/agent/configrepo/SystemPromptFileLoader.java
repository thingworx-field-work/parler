package com.thingworx.things.agent.configrepo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;

import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.constants.CommonPropertyNames;

/** Loads optional whole-block stable system prompt from {@code /SystemPrompt/*.md}. */
public final class SystemPromptFileLoader {

    public static final String SYSTEM_PROMPT_LISTING_PATH = ConfigurationRepositoryPaths.SYSTEM_PROMPT_ROOT;

    private SystemPromptFileLoader() {}

    public static ExternalSystemPromptSelection load(RepositoryReader reader, String repoName, Logger log) {
        if (reader == null) {
            return ExternalSystemPromptSelection.defaultSelection();
        }
        InfoTable listing;
        try {
            listing = reader.getFileListing(SYSTEM_PROMPT_LISTING_PATH, "");
        } catch (Exception e) {
            String msg = "list_failed: " + e.getMessage();
            if (log != null) {
                log.error("[{}] configurationRepository: SystemPrompt list failed: {}", repoName, e.getMessage(), e);
            }
            return ExternalSystemPromptSelection.fallback(msg);
        }
        List<String> markdownFiles = extractMarkdownFileNames(listing);
        if (markdownFiles.isEmpty()) {
            return ExternalSystemPromptSelection.defaultSelection();
        }
        if (markdownFiles.size() > 1) {
            String names = String.join(", ", markdownFiles);
            if (log != null) {
                log.error("[{}] configurationRepository: multiple SystemPrompt markdown files ({}); using default assembly",
                        repoName, names);
            }
            return ExternalSystemPromptSelection.fallback("multiple_files: " + names);
        }
        String fileName = markdownFiles.get(0);
        String path = SYSTEM_PROMPT_LISTING_PATH + "/" + fileName;
        RepositoryTextLoads.Result loaded = RepositoryTextLoads.loadText(reader, path);
        if (loaded.kind() == RepositoryTextLoads.Kind.READ_ERROR) {
            if (log != null) {
                log.error("[{}] configurationRepository: SystemPrompt read failed for {}: {}",
                        repoName, path, loaded.errorMessage());
            }
            return ExternalSystemPromptSelection.fallback("read_error: " + path);
        }
        if (loaded.kind() == RepositoryTextLoads.Kind.MISSING) {
            if (log != null) {
                log.error("[{}] configurationRepository: SystemPrompt file missing after listing: {}",
                        repoName, path);
            }
            return ExternalSystemPromptSelection.fallback("missing_file: " + path);
        }
        String text = loaded.text();
        if (text == null || text.isBlank()) {
            if (log != null) {
                log.error("[{}] configurationRepository: SystemPrompt file {} is empty; using default assembly",
                        repoName, path);
            }
            return ExternalSystemPromptSelection.fallback("empty_file: " + path);
        }
        return ExternalSystemPromptSelection.externalFile(path, text);
    }

    static List<String> extractMarkdownFileNames(InfoTable listing) {
        List<String> files = new ArrayList<>();
        if (listing == null || listing.getRowCount() == 0) {
            return files;
        }
        for (int i = 0; i < listing.getRowCount(); i++) {
            ValueCollection row = (ValueCollection) listing.getRow(i);
            if (row == null) {
                continue;
            }
            String name = cellString(row, CommonPropertyNames.PROP_NAME);
            if (name == null || name.isBlank()) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".md")) {
                continue;
            }
            String fileType = cellString(row, CommonPropertyNames.PROP_FILETYPE);
            if (fileType != null && "D".equalsIgnoreCase(fileType)) {
                continue;
            }
            files.add(name);
        }
        return files;
    }

    private static String cellString(ValueCollection row, String key) {
        if (row == null || key == null) {
            return null;
        }
        Object v = row.getValue(key);
        return v != null ? String.valueOf(v) : null;
    }
}
