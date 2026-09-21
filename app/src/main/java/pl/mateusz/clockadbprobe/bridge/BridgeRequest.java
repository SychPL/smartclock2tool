package pl.mateusz.clockadbprobe.bridge;

import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * Whether an incoming call is a request at all (SPEC 0.12 pkt 4.1).
 *
 * <p>An explicit intent stops another app from intercepting the call, but it does not stop anyone from making one:
 * an exported activity can be started by everybody. So the action, the field types, the flags and the identity of
 * the caller are all checked here, in code, before anything else happens.
 *
 * <p>The input is a plain record filled in by the Android adapter, which is what makes this testable on a JVM.
 */
public final class BridgeRequest {
    public static final String ACTION = "pl.mateusz.clockadbprobe.action.BRIDGE";
    public static final int API = 1;

    private static final Pattern OP_ID = Pattern.compile("[0-9a-f]{32}");

    /** Everything the activity can see about the call, already converted to plain values. */
    public static final class Input {
        public String action;
        public boolean hasApi;
        public int api = -1;
        public String op = "";
        public String args = "";
        public String opId = "";
        public int flags;
        public boolean hasCaller;
        public boolean forwardResult;
        public boolean viaNewIntent;
        public int userId;
        public boolean hasUri;
    }

    public final String error;      // null when the request is well formed
    public final String op;
    public final String args;
    public final String opId;

    private BridgeRequest(String error, String op, String args, String opId) {
        this.error = error;
        this.op = op;
        this.args = args;
        this.opId = opId;
    }

    public static BridgeRequest parse(Input in) {
        if (in == null) return bad("unsupported");
        // who is calling comes first: without an identity nothing else can be judged
        if (!in.hasCaller) return bad("denied");
        if (in.forwardResult) return bad("denied");     // the result would go to someone else
        if (in.viaNewIntent) return bad("denied");      // arguments must not reach a screen that already has an identity
        if (!ACTION.equals(in.action)) return bad("unsupported");
        if (in.userId != 0) return bad("unsupported");  // the contract covers user 0 only

        // The system adds flags of its own to a delivered intent (which window brought it up, and so on), so the
        // rule is a list of flags that change who owns the result or which screen serves it, not "nothing else".
        if ((in.flags & REFUSED_FLAGS) != 0) return bad("denied");
        boolean grant = (in.flags & FLAG_GRANT_READ_URI_PERMISSION) != 0;
        if (grant && !Ops.needsFile(in.op)) return bad("denied");

        if (!in.hasApi) return bad("unsupported");
        if (in.api != API) return bad("unsupported_api");
        if (!Ops.known(in.op)) return bad("unsupported");
        if (in.opId == null || !OP_ID.matcher(in.opId).matches()) return bad("unsupported");

        String args = in.args == null ? "" : in.args.trim();
        if (!args.isEmpty()) {
            try {
                new JSONObject(args);
            } catch (Exception e) {
                return bad("unsupported");
            }
        }
        if (Ops.needsFile(in.op) != in.hasUri) return bad("unsupported");

        return new BridgeRequest(null, in.op, args, in.opId);
    }

    /** Kept here so the pure class does not have to import android.content.Intent. */
    public static final int FLAG_GRANT_READ_URI_PERMISSION = 0x00000001;
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public static final int FLAG_ACTIVITY_SINGLE_TOP = 0x20000000;
    public static final int FLAG_ACTIVITY_CLEAR_TOP = 0x04000000;
    public static final int FLAG_ACTIVITY_MULTIPLE_TASK = 0x08000000;
    public static final int FLAG_ACTIVITY_NEW_DOCUMENT = 0x00080000;
    public static final int FLAG_ACTIVITY_CLEAR_TASK = 0x00008000;
    public static final int FLAG_ACTIVITY_FORWARD_RESULT = 0x02000000;

    /** Flags that would move the request to another task, another screen, or another recipient of the result. */
    static final int REFUSED_FLAGS = FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP | FLAG_ACTIVITY_CLEAR_TOP
            | FLAG_ACTIVITY_MULTIPLE_TASK | FLAG_ACTIVITY_NEW_DOCUMENT | FLAG_ACTIVITY_CLEAR_TASK
            | FLAG_ACTIVITY_FORWARD_RESULT;

    private static BridgeRequest bad(String error) {
        return new BridgeRequest(error, "", "", "");
    }
}
