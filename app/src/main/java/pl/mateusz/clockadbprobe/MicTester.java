package pl.mateusz.clockadbprobe;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * 5-second AudioRecord probe: 16 kHz mono 16-bit. Writes WAV to private storage.
 */
public final class MicTester {

    public static final int SAMPLE_RATE = 16000;
    public static File lastWav = null;
    public static boolean lastWavValid = false;

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested at runtime by MainActivity
    public static void test(Context ctx, Report rep) {
        int minBuf;
        try {
            minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rep.log("MICROPHONE TEST", "getMinBufferSize = " + minBuf);
        } catch (Throwable t) {
            rep.exception("MicTester.getMinBufferSize", t);
            return;
        }
        if (minBuf <= 0) {
            rep.line("MICROPHONE TEST", "getMinBufferSize invalid (" + minBuf + ") — mic not usable with these params");
            return;
        }

        AudioRecord rec = null;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf * 4, 8192));
            rep.log("MICROPHONE TEST", "AudioRecord state = " + rec.getState()
                    + " (1=INITIALIZED, 1=STATE_INITIALIZED)");
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rep.line("MICROPHONE TEST", "AudioRecord NOT initialized — RECORD_AUDIO permission missing or mic busy");
                rec.release();
                return;
            }
            rec.startRecording();
            rep.log("MICROPHONE TEST", "startRecording -> recordingState = " + rec.getRecordingState()
                    + " (3=RECORDING)");

            int seconds = 5;
            int totalSamples = 0;
            double sumSq = 0;
            int peak = 0;
            byte[] wav = recordWav(ctx, rec, seconds);
            lastWav = (File) wavTag[0]; lastWavValid = (Boolean) wavTag[1];
            // Metrics computed inside recordWav below via captured counters.
            rep.line("MICROPHONE TEST", lastWavMetrics);

            rec.stop();
        } catch (Throwable t) {
            rep.exception("MicTester.run", t);
        } finally {
            if (rec != null) rec.release();
        }
    }

    // Small hack to pass metrics out of the loop without a callback class.
    private static Object[] wavTag = new Object[2];
    private static String lastWavMetrics = "";

    private static byte[] recordWav(Context ctx, AudioRecord rec, int seconds) {
        int frames = SAMPLE_RATE * seconds;
        byte[] data = new byte[frames * 2];
        int off = 0;
        double sumSq = 0;
        int peak = 0;
        while (off < data.length) {
            int n = rec.read(data, off, Math.min(4096, data.length - off));
            if (n <= 0) {
                lastWavMetrics = "read() returned " + n + " after " + off + " bytes";
                break;
            }
            for (int i = 0; i + 1 < n; i += 2) {
                int s = (short) ((data[off + i] & 0xff) | (data[off + i + 1] << 8));
                sumSq += (double) s * s;
                int a = Math.abs(s);
                if (a > peak) peak = a;
            }
            off += n;
        }
        int samples = off / 2;
        double rms = samples > 0 ? Math.sqrt(sumSq / samples) : 0;
        File f = null; boolean valid = off > 0;
        if (valid) {
            try {
                f = new File(ctx.getExternalFilesDir(null) != null
                        ? ctx.getExternalFilesDir(null) : ctx.getFilesDir(), "mic_test.wav");
                OutputStream os = new FileOutputStream(f);
                writeWavHeader(os, off);
                os.write(data, 0, off);
                os.close();
            } catch (Throwable t) {
                Report.get().exception("MicTester.wav", t);
                valid = false;
            }
        }
        wavTag[0] = f; wavTag[1] = valid;
        lastWavMetrics = "samples=" + samples + " RMS=" + String.format("%.1f", rms)
                + " peak=" + peak + " (16-bit scale, 32767 max)"
                + (f != null && valid ? " WAV saved: " + f.getAbsolutePath() : " WAV not saved");
        return data;
    }

    private static void writeWavHeader(OutputStream os, int dataLen) throws java.io.IOException {
        int totalLen = 36 + dataLen;
        os.write("RIFF".getBytes("US-ASCII")); os.write(leInt(totalLen));
        os.write("WAVE".getBytes("US-ASCII"));
        os.write("fmt ".getBytes("US-ASCII")); os.write(leInt(16));
        os.write(leShort((short) 1)); os.write(leShort((short) 1));
        os.write(leInt(SAMPLE_RATE)); os.write(leInt(SAMPLE_RATE * 2));
        os.write(leShort((short) 2)); os.write(leShort((short) 16));
        os.write("data".getBytes("US-ASCII")); os.write(leInt(dataLen));
    }

    private static byte[] leInt(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    private static byte[] leShort(short v) {
        return new byte[]{(byte) v, (byte) (v >> 8)};
    }

    public static void audioHardwareInfo(Context ctx, Report rep) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            rep.line("AUDIO", "AudioManager: NOT ACCESSIBLE FROM APP");
            return;
        }
        try {
            rep.line("AUDIO", "PROPERTY_OUTPUT_SAMPLE_RATE = " + am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE));
            rep.line("AUDIO", "PROPERTY_OUTPUT_FRAMES_PER_BUFFER = " + am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER));
        } catch (Throwable t) {
            rep.exception("MicTester.audioProps", t);
        }
        try {
            android.media.AudioDeviceInfo[] devs = am.getDevices(AudioManager.GET_DEVICES_INPUTS | AudioManager.GET_DEVICES_OUTPUTS);
            for (android.media.AudioDeviceInfo d : devs) {
                String kind = (d.isSink() ? "OUTPUT " : "INPUT  ") + d.getType() + " " + d.getProductName();
                int[] rates = d.getSampleRates();
                rep.line("AUDIO", kind + (rates != null && rates.length > 0 ? " rates=" + java.util.Arrays.toString(rates) : ""));
            }
        } catch (Throwable t) {
            rep.exception("MicTester.audioDevices", t);
        }
    }
}
