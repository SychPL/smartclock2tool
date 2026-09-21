package pl.mateusz.clockadbprobe.bridge;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TrustStoreTest {
    private final FakeStore store = new FakeStore();

    private TrustStore trusting() {
        TrustStore t = new TrustStore(store);
        t.trust("p", "AA");
        return t;
    }

    @Test
    public void nobodyIsTrustedUntilTheUserSaysSo() {
        TrustStore t = new TrustStore(store);
        assertFalse(t.trusted("pl.mateusz.helios", "AA"));
        assertFalse("not even the app this bridge exists for", t.consented("pl.mateusz.helios", "AA", "state", ""));
    }

    @Test
    public void aChangedSignatureInheritsNothing() {
        TrustStore t = trusting();
        t.consent("p", "AA", "grant_permission", "android.permission.RECORD_AUDIO");
        assertTrue(t.consented("p", "AA", "grant_permission", "android.permission.RECORD_AUDIO"));

        assertFalse("a new signature is a new app", t.trusted("p", "BB"));
        t.trust("p", "BB");
        assertFalse("consents of the old signature are gone",
                t.consented("p", "BB", "grant_permission", "android.permission.RECORD_AUDIO"));
    }

    @Test
    public void detectingAChangedSignatureDropsTheOldConsentsEvenIfTheUserRefuses() {
        TrustStore t = trusting();
        t.consent("p", "AA", "adb_off", "");

        t.seenFingerprint("p", "BB");                 // noticed on an incoming request, user has not answered yet
        assertFalse("the app that asked is not the app that was trusted", t.trusted("p", "BB"));
        assertFalse("and the old consents must not survive the discovery", t.consented("p", "AA", "adb_off", ""));

        t.trust("p", "AA");                           // the old build comes back
        assertFalse("nothing is inherited backwards either", t.consented("p", "AA", "adb_off", ""));
    }

    @Test
    public void consentIsBoundToTheScope() {
        TrustStore t = trusting();
        t.consent("p", "AA", "grant_permission", "android.permission.RECORD_AUDIO");
        assertFalse("a permission added to the list later is not covered",
                t.consented("p", "AA", "grant_permission", "android.permission.CAMERA"));
        assertFalse("another operation is not covered", t.consented("p", "AA", "set_home", ""));
    }

    @Test
    public void revokingBumpsTheRevisionSoPendingRequestsCanBeInvalidated() {
        TrustStore t = trusting();
        t.consent("p", "AA", "adb_off", "");
        long before = t.revision();
        t.revokeOne("p", "adb_off", "");
        assertTrue(t.revision() > before);
        assertFalse(t.consented("p", "AA", "adb_off", ""));
        assertTrue("revoking one consent is not revoking trust", t.trusted("p", "AA"));

        long beforeAll = t.revision();
        t.revokeAll("p");
        assertTrue(t.revision() > beforeAll);
        assertFalse(t.trusted("p", "AA"));
    }

    @Test
    public void theStoreSurvivesAReload() {
        TrustStore t = trusting();
        t.consent("p", "AA", "adb_off", "");
        TrustStore reloaded = new TrustStore(store);
        assertTrue(reloaded.trusted("p", "AA"));
        assertTrue(reloaded.consented("p", "AA", "adb_off", ""));
    }

    @Test
    public void everyTrustedAppCanBeShownToTheUser() {
        TrustStore t = trusting();
        t.consent("p", "AA", "grant_permission", "android.permission.RECORD_AUDIO");
        List<String> lines = t.describe();
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).startsWith("p ("));
        assertTrue(lines.get(0).contains("grant_permission: android.permission.RECORD_AUDIO"));
    }

    static final class FakeStore implements TrustStore.Store {
        private String data = "";
        public String read() { return data; }
        public void write(String text) { data = text; }
    }
}
