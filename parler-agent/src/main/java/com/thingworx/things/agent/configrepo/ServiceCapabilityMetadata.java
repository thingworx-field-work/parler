package com.thingworx.things.agent.configrepo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Parsed U7 / G13 capability fields for one extended-tool entry. Absent metadata means a legacy
 * entry with no capability descriptor (SPR-2 must not advertise such entries as governed
 * capabilities that require risk).
 */
public final class ServiceCapabilityMetadata {

    private final String purpose;
    private final String capabilityVersion;
    private final ServiceCapabilityRisk risk;
    private final boolean dryRunSupported;
    private final String dryRunParameter;
    private final ServiceIdempotencyMode idempotencyMode;
    private final String idempotencyParameter;
    private final List<String> dataClassification;
    private final ServiceCapabilityAdmission admission;
    private final boolean enabled;
    private final String inputSchemaDigest;
    private final String inputProfileRef;
    private final String outputSchemaDigest;
    private final String outputProfileRef;
    private final List<String> businessErrors;
    private final ServiceCapabilityRuntimeState runtimeState;

    public ServiceCapabilityMetadata(
            String purpose,
            String capabilityVersion,
            ServiceCapabilityRisk risk,
            boolean dryRunSupported,
            String dryRunParameter,
            ServiceIdempotencyMode idempotencyMode,
            String idempotencyParameter,
            List<String> dataClassification,
            ServiceCapabilityAdmission admission,
            boolean enabled,
            String inputSchemaDigest,
            String inputProfileRef,
            String outputSchemaDigest,
            String outputProfileRef,
            List<String> businessErrors,
            ServiceCapabilityRuntimeState runtimeState) {
        this.purpose = purpose != null ? purpose : "";
        this.capabilityVersion = capabilityVersion != null && !capabilityVersion.isBlank()
                ? capabilityVersion.trim()
                : "1";
        this.risk = risk;
        this.dryRunSupported = dryRunSupported;
        this.dryRunParameter = dryRunParameter != null ? dryRunParameter : "";
        this.idempotencyMode = idempotencyMode;
        this.idempotencyParameter = idempotencyParameter != null ? idempotencyParameter : "";
        this.dataClassification = dataClassification == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(dataClassification));
        this.admission = admission;
        this.enabled = enabled;
        this.inputSchemaDigest = inputSchemaDigest != null ? inputSchemaDigest : "";
        this.inputProfileRef = inputProfileRef != null ? inputProfileRef : "";
        this.outputSchemaDigest = outputSchemaDigest != null ? outputSchemaDigest : "";
        this.outputProfileRef = outputProfileRef != null ? outputProfileRef : "";
        this.businessErrors = businessErrors == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(businessErrors));
        this.runtimeState = runtimeState != null ? runtimeState : ServiceCapabilityRuntimeState.ACTIVE;
    }

    public String purpose() {
        return purpose;
    }

    public String capabilityVersion() {
        return capabilityVersion;
    }

    public Optional<ServiceCapabilityRisk> risk() {
        return Optional.ofNullable(risk);
    }

    public boolean dryRunSupported() {
        return dryRunSupported;
    }

    public String dryRunParameter() {
        return dryRunParameter;
    }

    public Optional<ServiceIdempotencyMode> idempotencyMode() {
        return Optional.ofNullable(idempotencyMode);
    }

    public String idempotencyParameter() {
        return idempotencyParameter;
    }

    public List<String> dataClassification() {
        return dataClassification;
    }

    public Optional<ServiceCapabilityAdmission> admission() {
        return Optional.ofNullable(admission);
    }

    public boolean enabled() {
        return enabled;
    }

    public String inputSchemaDigest() {
        return inputSchemaDigest;
    }

    public String inputProfileRef() {
        return inputProfileRef;
    }

    public String outputSchemaDigest() {
        return outputSchemaDigest;
    }

    public String outputProfileRef() {
        return outputProfileRef;
    }

    public List<String> businessErrors() {
        return businessErrors;
    }

    public ServiceCapabilityRuntimeState runtimeState() {
        return runtimeState;
    }

    public ServiceCapabilityMetadata withRuntimeState(ServiceCapabilityRuntimeState state) {
        return new ServiceCapabilityMetadata(
                purpose,
                capabilityVersion,
                risk,
                dryRunSupported,
                dryRunParameter,
                idempotencyMode,
                idempotencyParameter,
                dataClassification,
                admission,
                enabled,
                inputSchemaDigest,
                inputProfileRef,
                outputSchemaDigest,
                outputProfileRef,
                businessErrors,
                state);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ServiceCapabilityMetadata)) {
            return false;
        }
        ServiceCapabilityMetadata that = (ServiceCapabilityMetadata) o;
        return dryRunSupported == that.dryRunSupported
                && enabled == that.enabled
                && Objects.equals(purpose, that.purpose)
                && Objects.equals(capabilityVersion, that.capabilityVersion)
                && risk == that.risk
                && Objects.equals(dryRunParameter, that.dryRunParameter)
                && idempotencyMode == that.idempotencyMode
                && Objects.equals(idempotencyParameter, that.idempotencyParameter)
                && Objects.equals(dataClassification, that.dataClassification)
                && admission == that.admission
                && Objects.equals(inputSchemaDigest, that.inputSchemaDigest)
                && Objects.equals(inputProfileRef, that.inputProfileRef)
                && Objects.equals(outputSchemaDigest, that.outputSchemaDigest)
                && Objects.equals(outputProfileRef, that.outputProfileRef)
                && Objects.equals(businessErrors, that.businessErrors)
                && runtimeState == that.runtimeState;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                purpose,
                capabilityVersion,
                risk,
                dryRunSupported,
                dryRunParameter,
                idempotencyMode,
                idempotencyParameter,
                dataClassification,
                admission,
                enabled,
                inputSchemaDigest,
                inputProfileRef,
                outputSchemaDigest,
                outputProfileRef,
                businessErrors,
                runtimeState);
    }
}
