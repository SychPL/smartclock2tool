package pl.mateusz.clockadbprobe.bridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class BridgeRequestTest {
    private static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    private static final int FLAG_ACTIVITY_SINGLE_TOP = 0x20000000;
    private static final int FLAG_ACTIVITY_CLEAR_TOP = 0x04000000;
    private static final int FLAG_GRANT_READ = BridgeRequest.FLAG_GRANT_READ_URI_PERMISSION;

    private static BridgeRequest.Input ok() {
        BridgeRequest.Input in = new BridgeRequest.Input();
        in.action = BridgeRequest.ACTION;
        in.hasApi = true;
        in.api = BridgeRequest.API;
        in.op = Ops.STATE;
        in.args = "";
        in.opId = "0123456789abcdef0123456789abcdef";
        in.hasCaller = true;
        return in;
    }

    private static String errorOf(BridgeRequest.Input in) {
        return BridgeRequest.parse(in).error;
    }

    @Test
    public void onlyTheDocumentedShapeIsAccepted() {
        assertNull(errorOf(ok()));

        BridgeRequest.Input emptyArgs = ok();
        emptyArgs.args = "";
        assertNull("empty args are legal", errorOf(emptyArgs));

        BridgeRequest.Input foreignAction = ok();
        foreignAction.action = "android.intent.action.VIEW";
        assertEquals("unsupported", errorOf(foreignAction));

        BridgeRequest.Input otherApi = ok();
        otherApi.api = 2;
        assertEquals("unsupported_api", errorOf(otherApi));

        BridgeRequest.Input noApi = ok();
        noApi.hasApi = false;
        assertEquals("unsupported", errorOf(noApi));

        BridgeRequest.Input notAnOp = ok();
        notAnOp.op = "rm -rf";
        assertEquals("unsupported", errorOf(notAnOp));

        BridgeRequest.Input brokenArgs = ok();
        brokenArgs.args = "{nope";
        assertEquals("unsupported", errorOf(brokenArgs));

        BridgeRequest.Input badId = ok();
        badId.opId = "zazolc";
        assertEquals("unsupported", errorOf(badId));

        BridgeRequest.Input noId = ok();
        noId.opId = "";
        assertEquals("unsupported", errorOf(noId));

        BridgeRequest.Input otherUser = ok();
        otherUser.userId = 10;
        assertEquals("only user 0 is in scope", "unsupported", errorOf(otherUser));
    }

    @Test
    public void theCallerMustBeIdentifiableAndDirect() {
        BridgeRequest.Input anonymous = ok();
        anonymous.hasCaller = false;
        assertEquals("denied", errorOf(anonymous));

        BridgeRequest.Input forwarded = ok();
        forwarded.forwardResult = true;
        assertEquals("denied", errorOf(forwarded));

        BridgeRequest.Input redelivered = ok();
        redelivered.viaNewIntent = true;
        assertEquals("denied", errorOf(redelivered));

        for (int flag : new int[]{FLAG_ACTIVITY_NEW_TASK, FLAG_ACTIVITY_SINGLE_TOP, FLAG_ACTIVITY_CLEAR_TOP,
                BridgeRequest.FLAG_ACTIVITY_MULTIPLE_TASK, BridgeRequest.FLAG_ACTIVITY_NEW_DOCUMENT,
                BridgeRequest.FLAG_ACTIVITY_CLEAR_TASK}) {
            BridgeRequest.Input flagged = ok();
            flagged.flags = flag;
            assertEquals("denied", errorOf(flagged));
        }
    }

    @Test
    public void flagsTheSystemAddsItselfAreNotARefusal() {
        // a delivered intent carries bookkeeping flags nobody asked for; refusing those refuses every real call
        for (int flag : new int[]{0x00800000, 0x00400000, 0x00200000, 0x00100000, 0x00010000}) {
            BridgeRequest.Input flagged = ok();
            flagged.flags = flag;
            assertNull("flag " + Integer.toHexString(flag) + " must not be refused", errorOf(flagged));
        }
    }

    @Test
    public void aFileGrantIsAllowedOnlyForAnOperationThatTakesAFile() {
        BridgeRequest.Input install = ok();
        install.op = Ops.INSTALL_APK;
        install.flags = FLAG_GRANT_READ;
        install.hasUri = true;
        assertNull(errorOf(install));

        BridgeRequest.Input grantOnARead = ok();
        grantOnARead.flags = FLAG_GRANT_READ;
        grantOnARead.hasUri = true;
        assertEquals("a flag this operation may not carry is a refusal, not a malformed request",
                "denied", errorOf(grantOnARead));

        BridgeRequest.Input installWithoutFile = ok();
        installWithoutFile.op = Ops.INSTALL_APK;
        assertEquals("install_apk without a file is a malformed request", "unsupported", errorOf(installWithoutFile));

        BridgeRequest.Input fileOnARead = ok();
        fileOnARead.hasUri = true;
        assertEquals("unsupported", errorOf(fileOnARead));
    }

    @Test
    public void aWellFormedRequestKeepsItsFields() {
        BridgeRequest.Input in = ok();
        in.op = Ops.GRANT_PERMISSION;
        in.args = " {\"permission\":\"android.permission.RECORD_AUDIO\"} ";
        BridgeRequest request = BridgeRequest.parse(in);
        assertNull(request.error);
        assertEquals(Ops.GRANT_PERMISSION, request.op);
        assertEquals("{\"permission\":\"android.permission.RECORD_AUDIO\"}", request.args);
        assertEquals("0123456789abcdef0123456789abcdef", request.opId);
    }
}
