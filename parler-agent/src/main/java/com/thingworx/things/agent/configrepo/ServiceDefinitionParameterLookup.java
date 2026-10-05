package com.thingworx.things.agent.configrepo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.metadata.collections.FieldDefinitionCollection;

/**
 * {@link ServiceParameterLookup} over a ThingWorx {@link ServiceDefinition}.
 */
public final class ServiceDefinitionParameterLookup implements ServiceParameterLookup {

    private final ServiceDefinition serviceDefinition;

    public ServiceDefinitionParameterLookup(ServiceDefinition serviceDefinition) {
        this.serviceDefinition = serviceDefinition;
    }

    @Override
    public boolean hasParameter(String parameterName) {
        if (parameterName == null || parameterName.isBlank() || serviceDefinition == null) {
            return false;
        }
        String want = parameterName.trim();
        FieldDefinitionCollection params = serviceDefinition.getParameters();
        if (params == null || params.values() == null) {
            return false;
        }
        for (FieldDefinition fd : params.values()) {
            if (fd != null && want.equals(fd.getName())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String inputShapeDigest() {
        if (serviceDefinition == null) {
            return "";
        }
        FieldDefinitionCollection params = serviceDefinition.getParameters();
        if (params == null || params.values() == null) {
            return sha256Hex("params:0");
        }
        List<String> parts = new ArrayList<>();
        for (FieldDefinition fd : params.values()) {
            if (fd == null) {
                continue;
            }
            String name = fd.getName() != null ? fd.getName() : "";
            String base = fd.getBaseType() != null ? fd.getBaseType().name() : "";
            parts.add(name + ":" + base);
        }
        Collections.sort(parts);
        return sha256Hex(String.join("|", parts));
    }

    static String sha256Hex(String utf8) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(utf8.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
