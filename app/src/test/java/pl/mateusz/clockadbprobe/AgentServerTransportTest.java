package pl.mateusz.clockadbprobe;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AgentServerTransportTest {

    @Test
    public void requestLineAtLimitIsAccepted() throws Exception {
        String line = repeat('a', AgentServer.MAX_REQUEST_LINE_BYTES);

        assertEquals(line, AgentServer.readRequestLine(
                new ByteArrayInputStream((line + "\r\n").getBytes("US-ASCII"))));
    }

    @Test(expected = AgentServer.RequestLineTooLongException.class)
    public void requestLineBeyondLimitIsRejectedBeforeNewline() throws Exception {
        String line = repeat('a', AgentServer.MAX_REQUEST_LINE_BYTES + 1);

        AgentServer.readRequestLine(
                new ByteArrayInputStream((line + "\r\n").getBytes("US-ASCII")));
    }

    @Test
    public void connectionLimiterNeverAdmitsMoreThanItsCapacity() {
        AgentServer.ConnectionLimiter limiter = new AgentServer.ConnectionLimiter(2);

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
        limiter.release();
        assertTrue(limiter.tryAcquire());
    }

    @Test(expected = SocketTimeoutException.class)
    public void absoluteRequestDeadlineRejectsSlowContinuousInput() throws Exception {
        final AtomicLong nowNanos = new AtomicLong();
        InputStream slowInput = new ByteArrayInputStream("GET / HTTP/1.1\r\n".getBytes("US-ASCII")) {
            @Override public synchronized int read() {
                nowNanos.addAndGet(6_000_000L);
                return super.read();
            }
        };

        AgentServer.readRequestLine(slowInput, 5L,
                new AgentServer.NanoClock() {
                    @Override public long nanoTime() { return nowNanos.get(); }
                }, new AgentServer.ReadTimeoutSetter() {
                    @Override public void setTimeoutMillis(int timeoutMillis) {
                        // No socket is needed: the fake clock advances during each read.
                    }
                });
    }

    @Test
    public void requestHeadConsumesHeadersButLeavesFollowingBytes() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream((
                "GET /agent/status HTTP/1.1\r\n"
                + "Host: clock\r\n"
                + "Connection: close\r\n"
                + "\r\n"
                + "NEXT").getBytes("US-ASCII"));

        assertEquals("GET /agent/status HTTP/1.1",
                AgentServer.readRequestHead(input));
        assertEquals('N', input.read());
    }

    @Test
    public void requestHeadExactlyAtLimitIsAccepted() throws Exception {
        String head = requestHeadWithTotalBytes(AgentServer.MAX_REQUEST_HEAD_BYTES);

        assertEquals("GET / HTTP/1.1", AgentServer.readRequestHead(
                new ByteArrayInputStream(head.getBytes("US-ASCII"))));
    }

    @Test(expected = AgentServer.RequestHeadTooLongException.class)
    public void requestHeadOneByteBeyondLimitIsRejected() throws Exception {
        String head = requestHeadWithTotalBytes(AgentServer.MAX_REQUEST_HEAD_BYTES + 1);

        AgentServer.readRequestHead(
                new ByteArrayInputStream(head.getBytes("US-ASCII")));
    }

    @Test(expected = AgentServer.IncompleteRequestHeadException.class)
    public void eofAfterRequestLineIsRejected() throws Exception {
        AgentServer.readRequestHead(new ByteArrayInputStream(
                "GET / HTTP/1.1\r\n".getBytes("US-ASCII")));
    }

    @Test(expected = AgentServer.IncompleteRequestHeadException.class)
    public void eofInsideHeadersIsRejected() throws Exception {
        AgentServer.readRequestHead(new ByteArrayInputStream(
                "GET / HTTP/1.1\r\nHost: clock\r\n".getBytes("US-ASCII")));
    }

    @Test(expected = SocketTimeoutException.class)
    public void absoluteDeadlineCoversRequestLineAndAllHeaders() throws Exception {
        final AtomicLong nowNanos = new AtomicLong();
        InputStream slowInput = new ByteArrayInputStream((
                "GET / HTTP/1.1\r\nHost: clock\r\n\r\n").getBytes("US-ASCII")) {
            @Override public synchronized int read() {
                nowNanos.addAndGet(1_000_000L);
                return super.read();
            }
        };

        AgentServer.readRequestHead(slowInput, 20L,
                new AgentServer.NanoClock() {
                    @Override public long nanoTime() { return nowNanos.get(); }
                }, new AgentServer.ReadTimeoutSetter() {
                    @Override public void setTimeoutMillis(int timeoutMillis) {
                        // No socket is needed: the fake clock advances per byte.
                    }
                });
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static String requestHeadWithTotalBytes(int totalBytes) {
        String prefix = "GET / HTTP/1.1\r\nX-Pad: ";
        String suffix = "\r\n\r\n";
        int padding = totalBytes - prefix.length() - suffix.length();
        if (padding < 0) throw new IllegalArgumentException("head size too small");
        return prefix + repeat('a', padding) + suffix;
    }
}
