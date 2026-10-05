package com.thingworx.things.agent.cache;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;

import com.thingworx.things.agent.AgentBaseThing;
import com.thingworx.types.BaseTypes;

/**
 * User-invocable lab helpers for the FileRepository live checklist. Opaque byte round-trip only.
 * Not a product wire API.
 */
public final class ArtifactCacheLab {

    private ArtifactCacheLab() {}

    /**
     * Create/write/close/publish/open opaque bytes and compare the read payload to the input.
     * Returns JSON including {@code payloadMatch}. Service name retained for the checklist.
     */
    public static String textRoundTrip(AgentBaseThing agent, Logger log, String text) {
        try {
            ArtifactCache cache = ArtifactCacheCore.requireCache(agent, log);
            ArtifactAccessContext ctx = ArtifactAccessContextFactory.fromCurrentPrincipalWithNewScope();
            ArtifactCreateRequest req = ArtifactCreateRequest.builder(
                    ArtifactKind.TEXT, ArtifactSchemaNode.scalar(BaseTypes.STRING))
                    .producer("ParlerArtifactCacheLabTextRoundTrip")
                    .schemaHint("application/octet-stream")
                    .lineage("lab")
                    .complete(true)
                    .build();
            ArtifactIoLimits limits = ArtifactIoLimits.of(1_000_000, 100, 60_000, 8192);
            String expected = text == null ? "" : text;
            ArtifactWriter writer = cache.create(req, ctx, limits);
            writer.writeBytes(expected.getBytes(StandardCharsets.UTF_8));
            writer.close();
            ArtifactRef ref = cache.publish(writer, ctx);
            try (ArtifactReader reader = cache.open(ref, ctx, limits)) {
                byte[] got = readAll(reader);
                String actual = new String(got, StandardCharsets.UTF_8);
                boolean match = expected.equals(actual);
                return "{\"ok\":true,\"artifactId\":\"" + ref.artifactId()
                        + "\",\"byteCount\":" + reader.record().byteCount()
                        + ",\"kind\":\"" + reader.record().kind()
                        + "\",\"payloadMatch\":" + match + "}";
            }
        } catch (ArtifactCacheException e) {
            return "{\"ok\":false,\"code\":\"" + e.code() + "\",\"message\":\""
                    + escape(e.getMessage()) + "\"}";
        } catch (Exception e) {
            return "{\"ok\":false,\"code\":\"INTERNAL\",\"message\":\"" + escape(e.getMessage()) + "\"}";
        }
    }

    private static byte[] readAll(ArtifactReader reader) throws ArtifactCacheException {
        java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
        while (true) {
            byte[] chunk = reader.readBytes(4096);
            if (chunk.length == 0) {
                break;
            }
            acc.write(chunk, 0, chunk.length);
        }
        return acc.toByteArray();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
