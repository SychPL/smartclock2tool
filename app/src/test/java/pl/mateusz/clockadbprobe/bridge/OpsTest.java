package pl.mateusz.clockadbprobe.bridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class OpsTest {
    @Test
    public void requestIdentityCoversTheWholeRequestButScopeOnlyTheConsent() {
        assertEquals(Ops.requestDigest("install_apk", "", "sha-1"), Ops.requestDigest("install_apk", "", "sha-1"));
        assertNotEquals("a different file is a different request",
                Ops.requestDigest("install_apk", "", "sha-1"), Ops.requestDigest("install_apk", "", "sha-2"));
        assertNotEquals("and so are different arguments",
                Ops.requestDigest("install_apk", "{\"expect\":\"a\"}", null),
                Ops.requestDigest("install_apk", "{\"expect\":\"b\"}", null));
        assertNotEquals("dropping an argument changes the request too",
                Ops.requestDigest("install_apk", "{\"expect\":\"a\"}", null),
                Ops.requestDigest("install_apk", "", null));

        assertEquals("whitespace is not content",
                Ops.requestDigest("grant_permission", "{\"permission\":\"android.permission.RECORD_AUDIO\"}", null),
                Ops.requestDigest("grant_permission", "{ \"permission\" : \"android.permission.RECORD_AUDIO\" }", null));
        assertEquals("neither is key order",
                Ops.requestDigest("install_apk", "{\"a\":1,\"b\":2}", null),
                Ops.requestDigest("install_apk", "{\"b\":2,\"a\":1}", null));

        assertEquals("android.permission.RECORD_AUDIO",
                Ops.consentScope("grant_permission", "{\"permission\":\"android.permission.RECORD_AUDIO\"}"));
        assertEquals("a file operation asks every time, so its scope carries nothing",
                "", Ops.consentScope("install_apk", "{\"expect\":\"sha-1\"}"));
        assertEquals("", Ops.consentScope("adb_off", ""));
    }

    @Test
    public void everyOperationHasARiskClass() {
        assertEquals("high", Ops.risk("root_adb_on"));
        assertEquals("high", Ops.risk("install_apk"));
        assertEquals("read", Ops.risk("state"));
        assertEquals("normal", Ops.risk("adb_off"));
        assertEquals("normal", Ops.risk("mic_release"));
        assertFalse(Ops.known("format_disk"));
        assertEquals("", Ops.risk("format_disk"));
    }

    @Test
    public void machineStagesHaveLimitsAndWaitingForAHumanDoesNot() {
        assertEquals(3_000, Ops.machineLimitMs("state", "running"));
        assertEquals(45_000, Ops.machineLimitMs("adb_off", "running"));
        assertEquals(240_000, Ops.machineLimitMs("root_adb_on", "running"));
        assertEquals(60_000, Ops.machineLimitMs("install_apk", "copying"));
        assertEquals(120_000, Ops.machineLimitMs("install_apk", "installing"));
        assertEquals("waiting for a human has no limit", 0, Ops.machineLimitMs("install_apk", "awaiting_consent"));
        assertEquals(0, Ops.machineLimitMs("root_adb_on", "accepted"));
    }

    @Test
    public void onlyTheInstallTakesAFileAndOnlyTheChainCaresAboutFirmware() {
        assertTrue(Ops.needsFile("install_apk"));
        assertFalse(Ops.needsFile("state"));
        assertTrue(Ops.needsFirmware("root_adb_on"));
        assertFalse("reading state works on any firmware", Ops.needsFirmware("state"));
        assertFalse(Ops.needsFirmware("adb_off"));
    }

    @Test
    public void theGrantableListAndTheMicTargetsAreClosed() {
        assertTrue(Ops.GRANTABLE.contains("android.permission.RECORD_AUDIO"));
        assertFalse(Ops.GRANTABLE.contains("android.permission.CAMERA"));
        assertTrue(Ops.MIC_TARGETS.contains("com.google.android.apps.mediashell"));
        assertEquals("the caller never chooses these", 2, Ops.MIC_TARGETS.size());
    }
}
