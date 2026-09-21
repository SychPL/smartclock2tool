package pl.mateusz.clockadbprobe.bridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DetailTest {
    @Test
    public void detailNeverCarriesSecretsOrPrivatePaths() {
        assertFalse(Detail.clean("token=eyJhbGciOiJIUzI1NiJ9.abc.def").contains("eyJ"));
        assertFalse(Detail.clean("Authorization: eyJhbGciOiJIUzI1NiJ9.abc.def").contains("eyJ"));
        assertFalse(Detail.clean("failed at /data/user/0/pl.mateusz.helios/files/x").contains("/data/"));
        assertFalse(Detail.clean("wrote /sdcard/Download/update.apk").contains("/sdcard/"));
        assertFalse(Detail.clean("key 0123456789abcdef0123456789abcdef").contains("0123456789abcdef"));
        assertFalse(Detail.clean("password: hunter2").contains("hunter2"));
    }

    @Test
    public void whatIsLeftStillTellsTheHumanSomething() {
        assertTrue(Detail.clean("chain failed: mode 3 write FAILED after 8 attempts").startsWith("chain failed"));
        assertEquals("adbwifi: ON", Detail.clean("adbwifi: ON"));
        assertEquals("a short hex value is not a secret", "pid 1234", Detail.clean("pid 1234"));
    }

    @Test
    public void oneSentenceMeansOneSentence() {
        StringBuilder long_ = new StringBuilder();
        for (int i = 0; i < 50; i++) long_.append("chain step ").append(i).append(" done. ");
        String cleaned = Detail.clean(long_.toString());
        assertTrue("one sentence, nothing more, got " + cleaned.length(), cleaned.length() <= 160);
        assertTrue(cleaned.endsWith("..."));
        assertFalse("newlines never survive", Detail.clean("first line\nsecond line").contains("\n"));
    }

    @Test
    public void nothingIsAlwaysSomethingPrintable() {
        assertEquals("", Detail.clean(null));
        assertEquals("", Detail.clean("   "));
    }
}
