package com.thingworx.things.agent.semantics;

import java.util.Objects;

/** Exact Property or governed Service binding (SP3). */
public final class SemanticRoleBinding {

    private final SemanticBindingKind kind;
    private final String propertyName;
    private final String thingName;
    private final String serviceName;
    private final String resultField;

    private SemanticRoleBinding(SemanticBindingKind kind, String propertyName, String thingName, String serviceName,
            String resultField) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.propertyName = propertyName;
        this.thingName = thingName;
        this.serviceName = serviceName;
        this.resultField = resultField;
    }

    public static SemanticRoleBinding property(String propertyName) {
        return new SemanticRoleBinding(SemanticBindingKind.PROPERTY, propertyName, null, null, null);
    }

    public static SemanticRoleBinding service(String thingName, String serviceName, String resultField) {
        return new SemanticRoleBinding(SemanticBindingKind.SERVICE, null, thingName, serviceName, resultField);
    }

    public SemanticBindingKind kind() {
        return kind;
    }

    public String propertyName() {
        return propertyName;
    }

    public String thingName() {
        return thingName;
    }

    public String serviceName() {
        return serviceName;
    }

    public String resultField() {
        return resultField;
    }
}
