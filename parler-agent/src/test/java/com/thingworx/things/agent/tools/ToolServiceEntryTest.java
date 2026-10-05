package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.common.RESTAPIConstants.StatusCode;
import com.thingworx.common.exceptions.InvalidRequestException;
import com.thingworx.entities.interfaces.IServiceProvider;
import com.thingworx.types.InfoTable;

/**
 * Service calls a tool reaches go through {@code processAPIServiceRequest} (the current user's ServiceInvoke); a
 * refusal is honored and never retried through {@code processServiceRequest} or a {@code *Direct} entry.
 */
class ToolServiceEntryTest {

    @Test
    void resolveDocumentSetOverrideIsInvokedAsTheCurrentUser() throws Exception {
        List<String> calls = new ArrayList<>();
        IServiceProvider agent = provider(calls, new InfoTable());

        DocumentKnowledgeRuntime.customResolverFor(agent).resolve("pump");

        assertEquals(List.of("processAPIServiceRequest:ResolveDocumentSet"), calls);
    }

    @Test
    void refusedResolveDocumentSetPropagatesWithoutAnotherEntry() {
        List<String> calls = new ArrayList<>();
        IServiceProvider agent = provider(calls, denial());

        assertThrows(InvalidRequestException.class,
                () -> DocumentKnowledgeRuntime.customResolverFor(agent).resolve("pump"));
        assertEquals(List.of("processAPIServiceRequest:ResolveDocumentSet"), calls);
    }

    @Test
    void propertyDefinitionServicesAreInvokedAsTheCurrentUser() {
        List<String> calls = new ArrayList<>();
        InfoTable definitions = DocumentKnowledgeIndexTest.multiDirectoryListing("Temperature");
        IServiceProvider entity = provider(calls, definitions);

        assertSame(definitions, PropertyDefinitionsService.fetch(entity));
        assertEquals(List.of("processAPIServiceRequest:GetPropertyDefinitions"), calls);
    }

    @Test
    void refusedPropertyDefinitionServicesYieldNothingWithoutAnotherEntry() {
        List<String> calls = new ArrayList<>();
        IServiceProvider entity = provider(calls, denial());

        assertNull(PropertyDefinitionsService.fetch(entity));
        assertEquals(List.of("processAPIServiceRequest:GetPropertyDefinitions",
                "processAPIServiceRequest:GetLocalPropertyDefinitions"), calls);
    }

    private static InvalidRequestException denial() {
        return new InvalidRequestException("Not authorized for ServiceInvoke", StatusCode.STATUS_UNAUTHORIZED);
    }

    /** Answers only the API entry; any other service entry fails the test. */
    private static IServiceProvider provider(List<String> calls, Object apiOutcome) {
        return (IServiceProvider) Proxy.newProxyInstance(IServiceProvider.class.getClassLoader(),
                new Class<?>[] {IServiceProvider.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("process")) {
                        calls.add(name + ":" + args[0]);
                        if (!"processAPIServiceRequest".equals(name)) {
                            throw new AssertionError("unexpected service entry " + name);
                        }
                        if (apiOutcome instanceof Exception) {
                            throw (Exception) apiOutcome;
                        }
                        return apiOutcome;
                    }
                    if ("toString".equals(name)) {
                        return "ProxyServiceProvider";
                    }
                    throw new AssertionError("unexpected call " + name);
                });
    }
}
