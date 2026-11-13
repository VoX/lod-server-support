package dev.vox.lss.common;

/**
 * Why a session answer says "not here" (0.23.0 plan §3): one reason, by precedence, so the
 * client can say which "switched off" it is instead of "for everyone, for this world, or for
 * you". Both server cores compute it here from the inputs that already decide {@code enabled}.
 *
 * <p>Exactly one reason, never two. A player the permission gate denies is never registered and
 * gets no later pushes, so "off in this world" could never be corrected when that player walks
 * into a world that serves others; they are told about the permission, or, when the operator
 * keeps that private, nothing at all.
 */
public final class SessionOffReason {
    private SessionOffReason() {}

    /**
     * @param serviceSwitchedOn {@code service.enabled} (the setting; not "a service object exists")
     * @param deniedByPermission the service gate denied this player
     * @param showPermissionRefusal {@code service.show_permission_refusal}
     * @param worldOff the session is registered and this world's distance is 0
     * @return one of the {@code SESSION_FLAG_OFF_*} bits, or 0 for "not said"
     */
    public static int of(boolean serviceSwitchedOn, boolean deniedByPermission,
                         boolean showPermissionRefusal, boolean worldOff) {
        if (!serviceSwitchedOn) return LSSConstants.SESSION_FLAG_OFF_SERVER;
        if (deniedByPermission) return showPermissionRefusal ? LSSConstants.SESSION_FLAG_OFF_PERMISSION : 0;
        return worldOff ? LSSConstants.SESSION_FLAG_OFF_WORLD : 0;
    }

    /**
     * The reason a client reads from an answer's flags. Reason bits beside {@code enabled=true}
     * are ignored, a frame without the flags field says nothing, and should a server ever set
     * two bits the client picks by the server's own precedence.
     */
    public static int read(boolean enabled, int sessionFlags) {
        if (enabled || sessionFlags < 0) return 0;
        if ((sessionFlags & LSSConstants.SESSION_FLAG_OFF_SERVER) != 0) return LSSConstants.SESSION_FLAG_OFF_SERVER;
        if ((sessionFlags & LSSConstants.SESSION_FLAG_OFF_PERMISSION) != 0) return LSSConstants.SESSION_FLAG_OFF_PERMISSION;
        if ((sessionFlags & LSSConstants.SESSION_FLAG_OFF_WORLD) != 0) return LSSConstants.SESSION_FLAG_OFF_WORLD;
        return 0;
    }
}
