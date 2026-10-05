package com.thingworx.things.agent.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.thingworx.things.agent.llm.ToolDefinition;

/**
 * OpenAI / Azure tool parameter schemas for {@code search_document_chunks},
 * {@code get_document_chunk}, and {@code resolve_document_set}. Normative contract:
 * {@code docs/agent/document-chunk-tools.md}.
 */
public final class DocumentKnowledgeToolSchemas {

    private DocumentKnowledgeToolSchemas() {}

    public static ToolDefinition searchDocumentChunksDef() {
        Map<String, Object> signalProps = new LinkedHashMap<>();
        signalProps.put("kind", Map.of("type", "string"));
        signalProps.put("name", Map.of("type", "string"));
        signalProps.put("value", Map.of("type", "string"));
        Map<String, Object> signalItem = new LinkedHashMap<>();
        signalItem.put("type", "object");
        signalItem.put("properties", signalProps);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", Map.of("type", "string",
                "description", "Natural-language query built from the user question or normalized health issue."));
        Map<String, Object> signalsArr = new LinkedHashMap<>();
        signalsArr.put("type", "array");
        signalsArr.put("description", "Optional alarms, properties, symptoms, or components.");
        signalsArr.put("items", signalItem);
        props.put("signals", signalsArr);
        props.put("assetContext", Map.of("type", "object",
                "description", "Optional asset context such as asset model, component, or document type hints."));
        Map<String, Object> documentTypesArr = new LinkedHashMap<>();
        documentTypesArr.put("type", "array");
        documentTypesArr.put("description",
                "Optional document type filters such as operations_manual or troubleshooting_guide.");
        documentTypesArr.put("items", Map.of("type", "string"));
        props.put("documentTypes", documentTypesArr);
        Map<String, Object> documentIdsArr = new LinkedHashMap<>();
        documentIdsArr.put("type", "array");
        documentIdsArr.put("description",
                "Optional explicit document id filter. When resolve_document_set returns a non-empty "
                        + "documents[], pass those documents[].documentId values here to scope this search to "
                        + "the resolved set (selectionMode documentIds-filter).");
        documentIdsArr.put("items", Map.of("type", "string"));
        props.put("documentIds", documentIdsArr);
        props.put("limit", Map.of("type", "integer",
                "description", "Maximum matches to return. Clamped to configured bounds."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of());

        return new ToolDefinition("search_document_chunks",
                "Find document chunks relevant to a health issue, alarm, component, or user question. "
                        + "Use after live status is known when the user needs manual/troubleshooting guidance. "
                        + "When resolve_document_set returned a non-empty documents[], pass those ids as "
                        + "documentIds to scope this search to the resolved set. "
                        + "Returns ranked metadata and snippets; call get_document_chunk for full markdown to cite.",
                schema, true);
    }

    public static ToolDefinition getDocumentChunkDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("docId", Map.of("type", "string"));
        props.put("chunkId", Map.of("type", "string"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("docId", "chunkId"));

        return new ToolDefinition("get_document_chunk",
                "Fetch full markdown and FileRepository source links for one chunk from search results. "
                        + "sourceLinks[].href is the canonical clickable PDF target (includes #page= when applicable); "
                        + "when citing this chunk in the final answer, copy each href into a markdown link "
                        + "[label](href). Do not cite manual text unless it came from this tool or a search snippet.",
                schema, true);
    }

    public static ToolDefinition resolveDocumentSetDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("key", Map.of("type", "string",
                "description", "Asset/Thing identifier to scope retrieval — typically the bound Thing's "
                        + "asset model or name."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("key"));

        return new ToolDefinition("resolve_document_set",
                "Resolve the bounded set of documents that apply to a given asset, to scope document search "
                        + "before search_document_chunks. Pass the asset model or Thing name as key. Returns "
                        + "documents[] (the in-scope document ids) and a resolverSource diagnostic. When documents "
                        + "is non-empty, pass documents[].documentId as the documentIds argument to "
                        + "search_document_chunks to scope retrieval. If documents is empty (resolverSource "
                        + "default-empty), no confident scope was found; proceed with a normal "
                        + "search_document_chunks call.",
                schema, true);
    }

    public static Map<String, Object> searchDocumentChunksParametersSchema() {
        return searchDocumentChunksDef().getParametersSchema();
    }
}
