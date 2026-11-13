package com.thingworx.things.agent.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.thingworx.things.agent.llm.ToolDefinition;

/**
 * OpenAI / Azure tool parameter schemas for {@code search_document_chunks},
 * {@code get_document_chunk}, {@code load_topic}, and {@code resolve_document_set}. Normative contract:
 * {@code docs/agent/document-chunk-tools.md}.
 */
public final class DocumentKnowledgeToolSchemas {

    private DocumentKnowledgeToolSchemas() {}

    public static ToolDefinition loadTopicDef() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("docId", Map.of("type", "string", "minLength", 1, "maxLength", 256));
        props.put("chunkId", Map.of("type", "string", "minLength", 1, "maxLength", 256));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("docId", "chunkId"));
        return new ToolDefinition("load_topic",
                "Load the complete method text of a search hit whose contentType is topic, using its exact docId and chunkId. "
                + "Ids must be 1–256 UTF-16 code units, without surrounding whitespace, control/line separators, .., /, or backslash, and cannot be a single dot. "
                + "Returns plain text with identity, SHA-256 of delivered UTF-8 text, character count and complete: true; "
                + "a body over 7000 UTF-16 code units is an error, never an excerpt. Loading grants no tool permission or approval; "
                + "follow the method using tools already admitted by the deployment. A non-empty injected document scope restricts loading.",
                schema, false);
    }

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
                "description", "Optional asset context such as asset model, component, or document type hints. "
                        + "To scope searches in this step to one unit's documents, put that unit's canonical Thing name "
                        + "in thingName; it is used only if it names a visible unit that has documents. "
                        + "To keep a unit across the conversation, call resolve_thing."));
        Map<String, Object> documentTypesArr = new LinkedHashMap<>();
        documentTypesArr.put("type", "array");
        documentTypesArr.put("description",
                "Optional document type filters such as operations_manual or troubleshooting_guide. "
                        + "Applies to matches, not to topics.");
        documentTypesArr.put("items", Map.of("type", "string"));
        props.put("documentTypes", documentTypesArr);
        Map<String, Object> documentIdsArr = new LinkedHashMap<>();
        documentIdsArr.put("type", "array");
        documentIdsArr.put("description",
                "Optional explicit document id filter. Omit it when a search result carried documentScopeSource. "
                        + "Otherwise pass the documents[].documentId values of a complete resolve_document_set "
                        + "result to scope this search to that set (selectionMode documentIds-filter); a result "
                        + "marked sampleOnly or rowsOmitted is not complete. Any value here, including an empty "
                        + "array, replaces the runtime scope.");
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
                        + "When a search result carries documentScopeSource, the runtime already scoped the search "
                        + "to the unit: omit documentIds. Otherwise pass the ids of a complete resolve_document_set "
                        + "result as documentIds. A resolve_document_set result marked sampleOnly or rowsOmitted "
                        + "lists only part of the set: do not pass it; omit documentIds, which uses the runtime "
                        + "scope when there is one and otherwise searches all documents. "
                        + "Returns ranked metadata and snippets; call get_document_chunk for full markdown to cite. "
                        + "A match with loadWith is a method: load it with that tool, because its text is not in the snippet. "
                        + "A non-empty topics array lists methods that match the request whatever the document "
                        + "ranking: load the first relevant one with load_topic.",
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
                        + "documents[] (the in-scope document ids) and a resolverSource diagnostic. When a search "
                        + "result already carried documentScopeSource, the runtime has scoped search to the unit and "
                        + "this call is not needed for scope. When documents is non-empty and the result is not "
                        + "marked sampleOnly or rowsOmitted, pass documents[].documentId as the documentIds argument "
                        + "to search_document_chunks. A marked result lists only part of the set: do not pass it; "
                        + "omit documentIds. If documents is empty (resolverSource "
                        + "default-empty), no confident scope was found; proceed with a normal "
                        + "search_document_chunks call.",
                schema, true);
    }

    public static Map<String, Object> searchDocumentChunksParametersSchema() {
        return searchDocumentChunksDef().getParametersSchema();
    }
}
