package pl.mateusz.clockadbprobe;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/** Locally generated test tones. No bundled or downloaded media. */
public final class SpeakerTester {

    private static final int RATE = 16000;

    public static void playTone(Context ctx, Report rep) {
        play(ctx, generateTone(), rep, "SPEAKER TEST", "2s 440 Hz sine");
    }

    /** Plays the WAV recorded by MicTester, if it exists and is valid. */
    public static boolean playRecording(Context ctx, Report rep) {
        File f = MicTester.lastWav;
        if (f == null || !f.exists() || !MicTester.lastWavValid) {
            rep.line("MICROPHONE TEST", "PLAY RECORDING: no valid recording available - run TEST MICROPHONE first");
            return false;
        }
        try {
            InputStream is = new FileInputStream(f);
            byte[] header = new byte[44];
            int h = is.read(header);
            if (h < 44) throw new IllegalStateException("short WAV header");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            play(ctx, bos.toByteArray(), rep, "MICROPHONE TEST", "recorded WAV playback");
            return true;
        } catch (Throwable t) {
            rep.exception("SpeakerTester.playRecording", t);
            return false;
        }
    }

    private static byte[] generateTone() {
        int samples = RATE * 2;
        byte[] pcm = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            double env = Math.min(1.0, Math.min(i, samples - 1) / (double) RATE); // 1s fade in/out
            short v = (short) (Math.sin(2 * Math.PI * 440 * i / RATE) * 12000 * env);
            pcm[i * 2] = (byte) v;
            pcm[i * 2 + 1] = (byte) (v >> 8);
        }
        return pcm;
    }

    private static void play(Context ctx, byte[] pcm, Report rep, String section, String what) {
        AudioTrack t = null;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            t = new AudioTrack(attrs, fmt, Math.max(pcm.length, 8192), AudioTrack.MODE_STATIC, 0);
            int written = t.write(pcm, 0, pcm.length);
            t.play();
            rep.log(section, "PLAYBACK (" + what + "): AudioTrack state=" + t.getState()
                    + " playState=" + t.getPlayState() + " written=" + written + " bytes @ " + RATE + " Hz");
            // MODE_STATIC track releases audio when stopped; wait roughly for duration.
            long ms = pcm.length / (RATE * 2 / 1000) / 2 * 2; // bytes -> ms (16-bit mono)
            Thread.sleep(Math.min(ms + 500, 5000));
        } catch (Throwable t2) {
            rep.exception("SpeakerTester.play", t2);
        } finally {
            if (t != null) t.release();
        }
    }
}
