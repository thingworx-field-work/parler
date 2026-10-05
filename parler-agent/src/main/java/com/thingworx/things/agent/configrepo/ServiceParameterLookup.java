package com.thingworx.things.agent.configrepo;

/**
 * Testable view of ServiceDefinition parameters for G13 dry-run / idempotency / digest checks.
 */
public interface ServiceParameterLookup {

    boolean hasParameter(String parameterName);

    /** Stable digest of input parameter names+baseTypes, or empty when unavailable. */
    String inputShapeDigest();
}
