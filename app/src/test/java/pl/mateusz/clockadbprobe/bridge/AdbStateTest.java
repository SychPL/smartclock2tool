package pl.mateusz.clockadbprobe.bridge;

import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AdbStateTest {
    @Test
    public void listeningIsDecidedByAConnectionNotByAProperty() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            assertTrue(AdbState.listening(server.getLocalPort(), 500));
        }
        try (ServerSocket closed = new ServerSocket(0)) {
            int port = closed.getLocalPort();
            closed.close();
            assertFalse(AdbState.listening(port, 300));
        }
    }

    @Test
    public void turningAdbOffIsJudgedBySilenceNotByTheScriptText() {
        assertEquals("ok", AdbState.offResult("adbwifi: OFF", false));
        assertEquals("a script that claims success while the port answers has not finished the job",
                "failed", AdbState.offResult("adbwifi: OFF", true));
        assertEquals("and a script that says nothing useful still counts when the port went quiet",
                "ok", AdbState.offResult("error: something", false));
    }

    @Test
    public void turningAdbOnIsJudgedTheSameWay() {
        assertEquals("ok", AdbState.onResult("adbwifi: ON", true));
        assertEquals("failed", AdbState.onResult("adbwifi: ON", false));
    }

    @Test
    public void micHoldersComeFromTheDumpAndOnlyForWatchedPackages() {
        List<String> watched = Arrays.asList("com.google.android.apps.mediashell", "com.google.assistant.launcher");
        String dump = ""
                + "RecordActivityMonitor dump time: 18:21:10\n"
                + "  session:9 -- source client=MIC, dev=2ch 48000Hz -- uid:10046 -- pack:com.google.android.apps.mediashell -- silenced:true\n"
                + "  session:33 -- source client=VOICE_RECOGNITION, dev=2ch 48000Hz -- uid:10060 -- pack:pl.mateusz.helios -- silenced:false\n";
        List<String> holders = AdbState.micHolders(dump, watched);
        assertEquals(1, holders.size());
        assertEquals("com.google.android.apps.mediashell", holders.get(0));
        assertFalse("the app asking is not a holder we report", holders.contains("pl.mateusz.helios"));
    }

    @Test
    public void nothingRecordingIsAnEmptyListButNoDumpIsUnknown() {
        List<String> watched = Arrays.asList("com.google.android.apps.mediashell");
        assertEquals(0, AdbState.micHolders("RecordActivityMonitor dump time: 18:21:10\n", watched).size());
        assertNull("without a privileged dump we do not know, and must not pretend", AdbState.micHolders(null, watched));
        assertNull(AdbState.micHolders("", watched));
    }
}
