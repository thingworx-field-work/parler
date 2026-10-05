package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.StringPrimitive;

class ParlerTableCsvPathCollisionsTest {

    @Test
    void isMissingParentDirectoryMessage_detectsPlatformPhrases() {
        assertTrue(ParlerTableCsvPathCollisions.isMissingParentDirectoryMessage("Directory does not exist: /a/b/"));
        assertTrue(ParlerTableCsvPathCollisions.isMissingParentDirectoryMessage("does not exist: /x/y"));
        assertFalse(ParlerTableCsvPathCollisions.isMissingParentDirectoryMessage("permission denied"));
        assertFalse(ParlerTableCsvPathCollisions.isMissingParentDirectoryMessage(null));
    }

    @Test
    void csvExceedsMaxChars_matchesProductionGuard() {
        assertFalse(ParlerTableCsvPathCollisions.csvExceedsMaxChars("ab", 10));
        assertTrue(ParlerTableCsvPathCollisions.csvExceedsMaxChars("abcdefghij", 9));
        assertFalse(ParlerTableCsvPathCollisions.csvExceedsMaxChars(null, 5));
    }

    @Test
    void resolveUniqueCsvPath_nullListing_treatsFileAbsent() throws Exception {
        ParlerTableCsvPathCollisions.FileListingLookup nullListing = (parent, mask) -> null;
        assertEquals("/u/20260101/x.csv",
                ParlerTableCsvPathCollisions.resolveUniqueCsvPath(nullListing, "/u/20260101/x.csv"));
    }

    @Test
    void repositoryFileExists_missingParentMessage_returnsFalse() throws Exception {
        ParlerTableCsvPathCollisions.FileListingLookup throwsMissing = (parent, mask) -> {
            throw new Exception("Directory does not exist: /no/such/");
        };
        assertFalse(ParlerTableCsvPathCollisions.repositoryFileExists(throwsMissing, "/no/such/file.csv"));
    }

    @Test
    void repositoryFileExists_listingHasMatchingName_returnsTrue() throws Exception {
        ParlerTableCsvPathCollisions.FileListingLookup hasFile =
                (parent, mask) -> listingWithNames("x.csv");
        assertTrue(ParlerTableCsvPathCollisions.repositoryFileExists(hasFile, "/u/20260101/x.csv"));
    }

    @Test
    void resolveUniqueCsvPath_preferredOccupied_selectsAlternateSuffix() throws Exception {
        ParlerTableCsvPathCollisions.FileListingLookup preferredOccupied = (parent, mask) -> {
            if ("/u/20260101/".equals(parent)) {
                return listingWithNames("x.csv");
            }
            return listingWithNames();
        };
        String chosen = ParlerTableCsvPathCollisions.resolveUniqueCsvPath(preferredOccupied, "/u/20260101/x.csv");
        assertNotNull(chosen);
        assertNotEquals("/u/20260101/x.csv", chosen);
        assertTrue(chosen.startsWith("/u/20260101/x_"), chosen);
        assertTrue(chosen.endsWith(".csv"), chosen);
    }

    private static InfoTable listingWithNames(String... names) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("name");
        fd.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fd);
        InfoTable it = new InfoTable(shape);
        for (String n : names) {
            ValueCollection row = new ValueCollection();
            row.put("name", new StringPrimitive(n));
            it.addRow(row);
        }
        return it;
    }
}
