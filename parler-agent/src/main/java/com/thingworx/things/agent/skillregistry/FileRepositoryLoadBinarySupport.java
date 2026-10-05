package com.thingworx.things.agent.skillregistry;

import com.thingworx.types.InfoTable;
import com.thingworx.types.primitives.BlobPrimitive;
import com.thingworx.types.primitives.ImagePrimitive;

/**
 * Normalizes {@link FileRepositoryThing} {@code LoadBinary} service results to raw bytes. The in-container Java path
 * often returns {@code byte[]}, but some platform / dispatch paths return an {@link InfoTable} wrapper with a
 * {@code Content} (or {@code result}) cell typed as {@link BlobPrimitive} / {@link ImagePrimitive}.
 */
public final class FileRepositoryLoadBinarySupport {

    private FileRepositoryLoadBinarySupport() {}

    /**
     * @return raw file bytes, or {@code null} when the service yields an empty or absent payload (mirrors missing-file
     *         semantics for fingerprint callers)
     */
    public static byte[] unwrap(Object out) throws Exception {
        if (out == null) {
            return null;
        }
        if (out instanceof byte[]) {
            return (byte[]) out;
        }
        if (out instanceof InfoTable) {
            InfoTable it = (InfoTable) out;
            if (it.getRowCount() == 0) {
                return null;
            }
            Object cell = it.getRow(0).getValue("Content");
            if (cell == null) {
                cell = it.getRow(0).getValue("result");
            }
            return cellToBytes(cell);
        }
        throw new IllegalStateException("LoadBinary returned unexpected type: " + out.getClass().getName());
    }

    private static byte[] cellToBytes(Object cell) throws Exception {
        if (cell == null) {
            return null;
        }
        if (cell instanceof byte[]) {
            return (byte[]) cell;
        }
        if (cell instanceof BlobPrimitive) {
            return ((BlobPrimitive) cell).getValue();
        }
        if (cell instanceof ImagePrimitive) {
            return ((ImagePrimitive) cell).getValue();
        }
        throw new IllegalStateException("LoadBinary Content cell has unsupported type: " + cell.getClass().getName());
    }
}
