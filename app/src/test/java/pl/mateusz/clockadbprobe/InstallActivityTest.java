package pl.mateusz.clockadbprobe;

import android.app.PendingIntent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class InstallActivityTest {

    @Test
    public void packageInstallerCallbackRemainsFillInCapable() {
        assertEquals(PendingIntent.FLAG_UPDATE_CURRENT,
                InstallActivity.installerCallbackFlags(27));
        assertEquals(PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE,
                InstallActivity.installerCallbackFlags(31));
    }
}
