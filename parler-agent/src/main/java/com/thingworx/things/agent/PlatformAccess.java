package com.thingworx.things.agent;

import com.thingworx.common.RESTAPIConstants.StatusCode;
import com.thingworx.common.exceptions.GenericHTTPException;
import com.thingworx.entities.RootEntity;
import com.thingworx.entities.interfaces.IServiceProvider;
import com.thingworx.entities.utils.EntityUtilities;
import com.thingworx.relationships.RelationshipTypes.ThingworxRelationshipTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;

/**
 * Thin forwarding to the platform entry that fits the caller; it adds no permission logic of its own. The
 * permission-free {@code *Direct} platform APIs appear only here, inside {@link #findProgrammatic} (followed by the
 * platform's own programmatic Visibility check) and {@link #findForPolicyCheck}.
 *
 * <ul>
 *   <li><b>As user</b> ({@link #findAsUser}, {@link #invokeAsUser}): REST semantics. The current user needs
 *       Visibility on the entity and ServiceInvoke on the service. Used for every target the user or the model
 *       chooses and every Service a tool call reaches: tools, approved writes, extended tools, agent Things named
 *       by a request, document-repository reads and the Agent's {@code ResolveDocumentSet}.</li>
 *   <li><b>Programmatic</b> ({@link #findProgrammatic}, {@link #invokeProgrammatic}): ThingWorx server-side
 *       semantics, the same rule platform scripts follow. The permission passes when the current user <em>or</em>
 *       the System user holds it. Used only for Parler's own infrastructure that no tool argument selects: its
 *       streams, DataTable, configuration and export repositories, LLM provider and connection Things, and the
 *       prompt-building {@code GetAlertPrompt}.</li>
 *   <li><b>Policy check</b> ({@link #findForPolicyCheck}): metadata lookup that ignores Visibility, used only by
 *       guards that can tighten a decision (mask or block a PASSWORD value). It never returns data to the caller
 *       or invokes a service, so it cannot grant access.</li>
 * </ul>
 *
 * A {@code null} security context (a thread the platform did not start) sees nothing; callers that leave the
 * request thread must carry the caller's context with them.
 */
public final class PlatformAccess {

    private PlatformAccess() {}

    public static RootEntity findAsUser(String name, ThingworxRelationshipTypes type) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        return EntityUtilities.findEntity(name, type);
    }

    public static RootEntity findProgrammatic(String name, ThingworxRelationshipTypes type) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        RootEntity entity = EntityUtilities.findEntityDirect(name, type);
        return entity != null && entity.isVisible(true) ? entity : null;
    }

    public static RootEntity findForPolicyCheck(String name, ThingworxRelationshipTypes type) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        return EntityUtilities.findEntityDirect(name, type);
    }

    public static InfoTable invokeAsUser(IServiceProvider target, String serviceName, ValueCollection params)
            throws Exception {
        return target.processAPIServiceRequest(serviceName, params);
    }

    public static InfoTable invokeProgrammatic(IServiceProvider target, String serviceName, ValueCollection params)
            throws Exception {
        return target.processServiceRequest(serviceName, params);
    }

    /** True when the platform itself refused a call for lack of permission (its 401 / 403 status). */
    public static boolean isPermissionDenial(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof GenericHTTPException) {
                StatusCode code = ((GenericHTTPException) t).getStatusCode();
                if (code == StatusCode.STATUS_UNAUTHORIZED || code == StatusCode.STATUS_FORBIDDEN) {
                    return true;
                }
            }
        }
        return false;
    }
}
