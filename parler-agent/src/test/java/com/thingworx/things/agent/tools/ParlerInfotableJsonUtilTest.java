package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.BaseTypes;

import org.junit.jupiter.api.Test;

/** Offline regression: PASSWORD wire {@code baseType} detection and LLM placeholder consistency ({@link com.thingworx.things.agent.tools.ParlerInfotableJsonUtil}). */
class ParlerInfotableJsonUtilTest {

    @Test
    void isPasswordBaseTypeName_trueForPassword() {
        assertTrue(ParlerInfotableJsonUtil.isPasswordBaseTypeName(BaseTypes.PASSWORD.name()));
    }

    @Test
    void isPasswordBaseTypeName_falseForOtherTypes() {
        assertFalse(ParlerInfotableJsonUtil.isPasswordBaseTypeName("STRING"));
        assertFalse(ParlerInfotableJsonUtil.isPasswordBaseTypeName(null));
        assertFalse(ParlerInfotableJsonUtil.isPasswordBaseTypeName(""));
    }

    @Test
    void passwordColumnLlmPlaceholder_matchesScalarInvokePath() {
        assertEquals("***", ParlerInfotableJsonUtil.passwordColumnLlmPlaceholder());
    }

    @Test
    void isPasswordColumn_shape_detects_password_field() {
        DataShapeDefinition dsd = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("pw");
        fd.setBaseType(BaseTypes.PASSWORD);
        fd.setOrdinal(0);
        dsd.addFieldDefinition(fd);
        assertTrue(ParlerInfotableJsonUtil.isPasswordColumn(dsd, "pw"));
        assertFalse(ParlerInfotableJsonUtil.isPasswordColumn(dsd, "other"));
    }
}
