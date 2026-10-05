package com.thingworx.things.agent.skillregistry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.BlobPrimitive;

class FileRepositoryLoadBinarySupportTest {

    @Test
    void unwrap_rawBytes() throws Exception {
        byte[] raw = {1, 2, 3};
        assertArrayEquals(raw, FileRepositoryLoadBinarySupport.unwrap(raw));
    }

    @Test
    void unwrap_infotable_blobPrimitiveContent() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(new FieldDefinition("Content", "", BaseTypes.BLOB));
        InfoTable it = new InfoTable(shape);
        byte[] payload = {9, 8, 7};
        ValueCollection row = new ValueCollection();
        row.put("Content", new BlobPrimitive(payload));
        it.addRow(row);
        assertArrayEquals(payload, FileRepositoryLoadBinarySupport.unwrap(it));
    }

    @Test
    void unwrap_emptyInfoTable_null() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(new FieldDefinition("Content", "", BaseTypes.BLOB));
        InfoTable it = new InfoTable(shape);
        assertNull(FileRepositoryLoadBinarySupport.unwrap(it));
    }

    @Test
    void unwrap_unsupportedWrapper_throws() {
        assertThrows(Exception.class, () -> FileRepositoryLoadBinarySupport.unwrap("not-binary"));
    }
}
