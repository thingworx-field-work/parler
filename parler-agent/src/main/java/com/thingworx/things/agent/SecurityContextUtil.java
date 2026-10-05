package com.thingworx.things.agent;

import com.thingworx.security.context.SecurityContext;
import com.thingworx.webservices.context.ThreadLocalContext;

/** Current TWX user for tenancy checks; empty absent principal. */
public final class SecurityContextUtil {

    private SecurityContextUtil() {}

    /** @return security principal name, or {@code null} if none */
    public static String currentPrincipalName() {
        try {
            SecurityContext ctx = ThreadLocalContext.getSecurityContext();
            if (ctx == null || ctx.getName() == null || ctx.getName().isEmpty()) {
                return null;
            }
            return ctx.getName();
        } catch (Exception e) {
            return null;
        }
    }
}
