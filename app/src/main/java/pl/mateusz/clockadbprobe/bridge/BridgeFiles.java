package pl.mateusz.clockadbprobe.bridge;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.UUID;

/**
 * Where the bridge keeps its state, and the production implementations of the seams the pure classes talk to.
 *
 * <p>Everything lives in {@code filesDir/bridge}. Writes go through a temporary file and a rename, so a crash in the
 * middle leaves the previous document intact rather than half of the new one.
 */
public final class BridgeFiles implements ExecutorLock.Files {
    private static BridgeFiles instance;

    private final File dir;
    private final BootIdClock bootClock;
    private OpRegistry registry;
    private TrustStore trust;
    private MicState mic;

    private BridgeFiles(Context context) {
        dir = new File(context.getFilesDir(), "bridge");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        bootClock = new BootIdClock(this);
    }

    /** One instance per process; the first call also installs the executor trace. */
    public static synchronized BridgeFiles get(Context context) {
        if (instance == null) {
            instance = new BridgeFiles(context.getApplicationContext());
            Executions.install(new ExecutorLock(instance, new ProcFs(), instance.bootClock));
        }
        return instance;
    }

    public File apkFor(String execId) {
        return new File(dir, execId + ".apk");
    }

    /** One registry per process, recovered once on first use. */
    public synchronized OpRegistry registry() {
        if (registry == null) {
            registry = new OpRegistry(new Document("registry.json"), new RegistryClock());
        }
        return registry;
    }

    public synchronized TrustStore trust() {
        if (trust == null) trust = new TrustStore(new Document("trust.json"));
        return trust;
    }

    public synchronized MicState mic() {
        if (mic == null) mic = new MicState(new Document("mic.json"));
        return mic;
    }

    public BridgeExecutor.MicStateHolder micState() {
        return () -> mic().state();
    }

    /** A JSON document in the bridge directory, written atomically. */
    private final class Document implements OpRegistry.Store, TrustStore.Store, MicState.Store {
        private final String name;

        Document(String name) {
            this.name = name;
        }

        public String read() {
            return BridgeFiles.this.read(name);
        }

        public void write(String text) {
            BridgeFiles.this.write(name, text);
        }
    }

    private final class RegistryClock implements OpRegistry.Clock {
        public long uptimeMs() {
            return SystemClock.elapsedRealtime();
        }

        public long wallMs() {
            return System.currentTimeMillis();
        }

        public String bootId() {
            return bootClock.bootId();
        }
    }

    @Override
    public synchronized String read(String name) {
        File file = new File(dir, name);
        if (!file.isFile()) return null;
        try (RandomAccessFile handle = new RandomAccessFile(file, "r")) {
            byte[] bytes = new byte[(int) handle.length()];
            handle.readFully(bytes);
            return new String(bytes, "UTF-8");
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public synchronized void write(String name, String text) {
        File target = new File(dir, name);
        File temp = new File(dir, name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(text.getBytes("UTF-8"));
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        //noinspection ResultOfMethodCallIgnored
        temp.renameTo(target);
    }

    @Override
    public synchronized void delete(String name) {
        //noinspection ResultOfMethodCallIgnored
        new File(dir, name).delete();
    }

    /** Process liveness read from /proc; the start time tells our child from a recycled identifier. */
    static final class ProcFs implements ExecutorLock.Processes {
        @Override
        public ExecutorLock.Liveness alive(int pid, long startTicks) {
            File stat = new File("/proc/" + pid + "/stat");
            if (!new File("/proc/self").exists()) return ExecutorLock.Liveness.UNKNOWN;  // no /proc, no answer
            if (!stat.exists()) return ExecutorLock.Liveness.DEAD;
            long actual = startTicksOf(stat);
            if (actual < 0) return ExecutorLock.Liveness.UNKNOWN;
            return actual == startTicks ? ExecutorLock.Liveness.ALIVE : ExecutorLock.Liveness.DEAD;
        }

        static long startTicksOf(File stat) {
            try (RandomAccessFile handle = new RandomAccessFile(stat, "r")) {
                byte[] bytes = new byte[2048];
                int read = handle.read(bytes);
                if (read <= 0) return -1;
                String line = new String(bytes, 0, read, "UTF-8");
                int afterName = line.lastIndexOf(british());                      // the name may contain spaces
                if (afterName < 0) return -1;
                String[] fields = line.substring(afterName + 1).trim().split("\\s+");
                // after the closing parenthesis the fields are shifted by two, so field 22 is index 19
                return fields.length > 19 ? Long.parseLong(fields[19]) : -1;
            } catch (Exception e) {
                return -1;
            }
        }

        private static char british() { return ')'; }
    }

    public static long startTicksOfSelf(int pid) {
        return ProcFs.startTicksOf(new File("/proc/" + pid + "/stat"));
    }

    /**
     * The identifier of this boot. The kernel one is the truth; when it cannot be read we mint one and keep it in the
     * bridge directory together with the uptime at which it was written, which is enough to notice a restart.
     */
    static final class BootIdClock implements ExecutorLock.Clock {
        private final BridgeFiles files;
        private String cached;

        BootIdClock(BridgeFiles files) {
            this.files = files;
        }

        @Override
        public synchronized String bootId() {
            if (cached != null) return cached;
            String kernel = readKernelBootId();
            if (kernel != null) {
                cached = kernel;
                return cached;
            }
            cached = fallback();
            return cached;
        }

        private String readKernelBootId() {
            try (RandomAccessFile handle = new RandomAccessFile("/proc/sys/kernel/random/boot_id", "r")) {
                String line = handle.readLine();
                return line == null || line.isEmpty() ? null : line.trim();
            } catch (Exception e) {
                return null;
            }
        }

        /** Without the kernel value: a stored identifier that is dropped whenever uptime went backwards. */
        private String fallback() {
            long uptime = SystemClock.elapsedRealtime();
            String stored = files.read("boot.json");
            if (stored != null) {
                try {
                    org.json.JSONObject json = new org.json.JSONObject(stored);
                    if (json.optLong("uptime_ms", Long.MAX_VALUE) <= uptime) return json.optString("boot_id");
                } catch (Exception ignored) {
                    // fall through and mint a new one
                }
            }
            String minted = UUID.randomUUID().toString();
            try {
                org.json.JSONObject json = new org.json.JSONObject();
                json.put("boot_id", minted);
                json.put("uptime_ms", uptime);
                files.write("boot.json", json.toString());
            } catch (Exception ignored) {
                // an unwritable fallback still works for this process
            }
            return minted;
        }
    }
}
