package com.thingworx.things.agent.semantics;

import java.util.Objects;

/** One validation or refresh diagnostic for the application semantic profile. */
public final class SemanticProfileDiagnostic {

    public enum Severity {
        WARNING, ERROR
    }

    private final Severity severity;
    private final String code;
    private final String message;

    public SemanticProfileDiagnostic(Severity severity, String code, String message) {
        this.severity = severity != null ? severity : Severity.WARNING;
        this.code = code != null ? code.trim() : "";
        this.message = message != null ? message : "";
    }

    public Severity severity() {
        return severity;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SemanticProfileDiagnostic)) {
            return false;
        }
        SemanticProfileDiagnostic that = (SemanticProfileDiagnostic) o;
        return severity == that.severity && Objects.equals(code, that.code) && Objects.equals(message, that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(severity, code, message);
    }
}
