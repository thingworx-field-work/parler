package com.thingworx.things.agent.configrepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.types.InfoTable;

class RepositoryFileFingerprintTest {

    @Test
    void sameBytesTwice_sameSha256() throws Exception {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return null;
            }

            @Override
            public String loadText(String path) {
                return "alpha";
            }

            @Override
            public byte[] loadBinary(String path) {
                return "alpha".getBytes(StandardCharsets.UTF_8);
            }
        };
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        RepositoryFileFingerprint.Result a = RepositoryFileFingerprint.capture(reader, "/x.txt", t);
        RepositoryFileFingerprint.Result b = RepositoryFileFingerprint.capture(reader, "/x.txt", t);
        assertEquals(RepositoryFileFingerprint.Status.present, a.status());
        assertEquals(a.sha256Hex(), b.sha256Hex());
        assertNotNull(a.sha256Hex());
    }

    @Test
    void oversized_noShaButReportsSize() throws Exception {
        byte[] huge = new byte[(int) RepositoryFileFingerprint.MAX_BYTES_FOR_SHA256 + 1];
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return null;
            }

            @Override
            public String loadText(String path) {
                return null;
            }

            @Override
            public byte[] loadBinary(String path) {
                return huge;
            }
        };
        RepositoryFileFingerprint.Result r =
                RepositoryFileFingerprint.capture(reader, "/big.bin", Instant.EPOCH);
        assertEquals(RepositoryFileFingerprint.Status.oversized_for_hash, r.status());
        assertNull(r.sha256Hex());
        assertEquals(huge.length, r.byteSize());
    }

    @Test
    void defaultLoadBinary_matchesUtf8OfLoadTextGivenAscii() throws Exception {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return null;
            }

            @Override
            public String loadText(String path) {
                return "{\"x\":1}";
            }
        };
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        RepositoryFileFingerprint.Result a = RepositoryFileFingerprint.capture(reader, "/tools/extended_tools.json", t);
        RepositoryFileFingerprint.Result b = RepositoryFileFingerprint.capture(reader, "/tools/extended_tools.json", t);
        assertEquals(a.sha256Hex(), b.sha256Hex());
        assertEquals(RepositoryFileFingerprint.Status.present, a.status());
    }
}
