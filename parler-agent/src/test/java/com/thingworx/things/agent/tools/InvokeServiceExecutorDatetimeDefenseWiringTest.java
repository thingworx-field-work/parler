package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.thingworx.things.agent.tools.InvokeServiceDatetimeLiteralDefense.RejectionReason;
import com.thingworx.types.BaseTypes;
import org.junit.jupiter.api.Test;

/**
 * Pins the wiring fix and the §5 step 3 fallback completion. An earlier implementation
 * threw {@code IllegalArgumentException(ERROR_PREFIX + …)} from inside the {@code DATETIME} case of
 * {@code InvokeServiceExecutor.jsonToPrimitive}; the surrounding {@code catch (Exception e)} wrapped that
 * into a generic {@code "Parameter X: cannot convert to DATETIME: …"} string and the wire error code
 * {@code UNSUPPORTED_RELATIVE_LITERAL} never fired — every defense rejection surfaced as
 * {@code INVALID_PARAMETERS}. The fix lives in {@link InvokeServiceArgumentCoercion}: defense runs <em>before</em>
 * the wrapping {@code try / catch} and uses the typed {@link UnsupportedRelativeLiteralException}.
 *
 * <p>Tests target {@link InvokeServiceArgumentCoercion} directly to avoid loading
 * {@link InvokeServiceExecutor}, whose {@code static} init pulls {@code LogUtilities} (only available
 * in-container). The thin {@code jsonToPrimitive} delegate in {@code InvokeServiceExecutor} is a single
 * call to {@code coerce(...)} plus a {@code DEBUG} log; behavior parity follows by construction.</p>
 */
class InvokeServiceExecutorDatetimeDefenseWiringTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void datetimeRawTodayThrowsTypedException() {
        UnsupportedRelativeLiteralException e = assertThrows(UnsupportedRelativeLiteralException.class,
                () -> InvokeServiceArgumentCoercion.coerce("startDate", new TextNode("today"), BaseTypes.DATETIME));
        assertEquals("startDate", e.getParamName());
        assertEquals("today", e.getRawValue());
        assertEquals(RejectionReason.DAY_TOKEN, e.getRejectionReason());
        assertNotNull(e.getDetail());
        // The fix: the message must NOT carry the old wrapping prefix.
        assertEquals(e.getDetail(), e.getMessage(),
                "typed exception must not be wrapped as 'Parameter X: cannot convert to DATETIME: …'");
    }

    @Test
    void datetimeNowMinusThrowsTyped() {
        UnsupportedRelativeLiteralException e = assertThrows(UnsupportedRelativeLiteralException.class,
                () -> InvokeServiceArgumentCoercion.coerce("endTime", new TextNode("now-5m"), BaseTypes.DATETIME));
        assertEquals("endTime", e.getParamName());
        assertEquals(RejectionReason.INFORMAL_RELATIVE, e.getRejectionReason());
    }

    @Test
    void datetimeYesterdayThrowsTyped() {
        UnsupportedRelativeLiteralException e = assertThrows(UnsupportedRelativeLiteralException.class,
                () -> InvokeServiceArgumentCoercion.coerce("startTime", new TextNode("yesterday"), BaseTypes.DATETIME));
        assertEquals("startTime", e.getParamName());
        assertEquals(RejectionReason.DAY_TOKEN, e.getRejectionReason());
    }

    @Test
    void datetimeThisMorningTaggedCalendar() {
        UnsupportedRelativeLiteralException e = assertThrows(UnsupportedRelativeLiteralException.class,
                () -> InvokeServiceArgumentCoercion.coerce("at", new TextNode("this morning"), BaseTypes.DATETIME));
        assertEquals(RejectionReason.CALENDAR_OR_WALL_CLOCK, e.getRejectionReason());
    }

    @Test
    void jsonScalarSlotWithDatetimeLikeNameDefended() {
        // §5 step 3 fallback (scalar): untyped JSON slot whose name matches §8 — apply same defense to text.
        UnsupportedRelativeLiteralException e = assertThrows(UnsupportedRelativeLiteralException.class,
                () -> InvokeServiceArgumentCoercion.coerce("eventTime", new TextNode("yesterday"), BaseTypes.JSON));
        assertEquals("eventTime", e.getParamName());
    }

    @Test
    void variantScalarSlotWithDatetimeLikeNameDefended() {
        UnsupportedRelativeLiteralException e = assertThrows(UnsupportedRelativeLiteralException.class,
                () -> InvokeServiceArgumentCoercion.coerce("timestamp", new TextNode("now-1h"), BaseTypes.VARIANT));
        assertEquals("timestamp", e.getParamName());
    }

    @Test
    void jsonObjectBagWithDatetimeLikeKeyDefended() throws Exception {
        // Bag-shaped JSON containing a §8 key with raw relative text
        // must be rejected even when the *parameter name itself* is a generic name like "payload".
        var node = MAPPER.readTree("{\"startDate\":\"today\",\"name\":\"thing\"}");
        UnsupportedRelativeLiteralException e = assertThrows(UnsupportedRelativeLiteralException.class,
                () -> InvokeServiceArgumentCoercion.coerce("payload", node, BaseTypes.JSON));
        // Path-style label identifies the offending key inside the bag.
        assertEquals("payload.startDate", e.getParamName());
        assertEquals("today", e.getRawValue());
        assertEquals(RejectionReason.DAY_TOKEN, e.getRejectionReason());
    }

    @Test
    void variantObjectBagWithDatetimeLikeKeyDefended() throws Exception {
        var node = MAPPER.readTree("{\"endTime\":\"now-30m\"}");
        UnsupportedRelativeLiteralException e = assertThrows(UnsupportedRelativeLiteralException.class,
                () -> InvokeServiceArgumentCoercion.coerce("query", node, BaseTypes.VARIANT));
        assertEquals("query.endTime", e.getParamName());
        assertEquals(RejectionReason.INFORMAL_RELATIVE, e.getRejectionReason());
    }

    @Test
    void jsonObjectBagPassesWhenKeyValuesAreClean() throws Exception {
        // ISO instants under §8 keys must not throw the defense; coercion proceeds.
        var node = MAPPER.readTree("{\"startDate\":\"2026-05-06T12:00:00.000Z\",\"endDate\":\"2026-05-06T13:00:00.000Z\"}");
        try {
            InvokeServiceArgumentCoercion.coerce("payload", node, BaseTypes.JSON);
        } catch (UnsupportedRelativeLiteralException e) {
            throw new AssertionError("defense must not fire on bag with clean ISO values; threw: " + e.getDetail());
        } catch (Throwable ignored) {
            // Other coercion failures (e.g. JSONPrimitive class init failures in offline tests) are acceptable;
            // only the defense path is asserted here.
        }
    }

    @Test
    void jsonObjectBagWithoutDatetimeLikeKeysNotDefended() throws Exception {
        // Object whose top-level keys do not match the §8 list must not be inspected (v1: shallow).
        var node = MAPPER.readTree("{\"name\":\"today\",\"label\":\"yesterday\"}");
        try {
            InvokeServiceArgumentCoercion.coerce("payload", node, BaseTypes.JSON);
        } catch (UnsupportedRelativeLiteralException e) {
            throw new AssertionError("defense must not fire on bag without §8-named keys");
        } catch (Throwable ignored) {
            // Other errors fine; only defense path asserted.
        }
    }

    @Test
    void jsonObjectBagNestedNotDefended() throws Exception {
        // v1 conservatism: the bag scan is one level deep. Nested objects are intentionally not
        // inspected; the test pins this scope so a future depth change is a deliberate spec decision.
        var node = MAPPER.readTree("{\"inner\":{\"startDate\":\"today\"}}");
        try {
            InvokeServiceArgumentCoercion.coerce("payload", node, BaseTypes.JSON);
        } catch (UnsupportedRelativeLiteralException e) {
            throw new AssertionError("v1 bag scan is one-level only; nested objects must not throw");
        } catch (Throwable ignored) {
            // Other errors fine.
        }
    }

    @Test
    void jsonScalarSlotWithUnrelatedNameNotDefended() {
        // Name does not match §8 list AND value is scalar text → defense skipped.
        try {
            InvokeServiceArgumentCoercion.coerce("payload", new TextNode("today"), BaseTypes.JSON);
        } catch (UnsupportedRelativeLiteralException e) {
            throw new AssertionError("defense must not fire on JSON scalar whose param name is not §8 DATETIME-like");
        } catch (Throwable ignored) {
            // Other coercion errors are fine.
        }
    }

    @Test
    void jsonObjectBagSkipsNonTextValuesUnderDatetimeLikeKeys() throws Exception {
        // §8-named key whose value is non-text (e.g. number) is out of scope for v1 — must not throw.
        var node = MAPPER.readTree("{\"timestamp\":1746547200000}");
        try {
            InvokeServiceArgumentCoercion.coerce("payload", node, BaseTypes.JSON);
        } catch (UnsupportedRelativeLiteralException e) {
            throw new AssertionError("non-text value under §8-named key must not throw the defense");
        } catch (Throwable ignored) {
            // Other errors fine.
        }
    }

    @Test
    void datetimeIsoValueProceedsToParse() {
        // Defense returns null on ISO; subsequent DateTime.parse() should succeed and yield a DatetimePrimitive.
        Object result = InvokeServiceArgumentCoercion.coerce("startDate", new TextNode("2026-05-06T12:00:00.000Z"),
                BaseTypes.DATETIME);
        assertNotNull(result);
        assertTrue(result.getClass().getSimpleName().toLowerCase().contains("datetime"),
                "expected a DatetimePrimitive on a clean ISO value; got " + result.getClass());
    }
}
