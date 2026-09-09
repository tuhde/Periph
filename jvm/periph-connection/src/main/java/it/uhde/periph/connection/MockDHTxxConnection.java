package it.uhde.periph.connection;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * In-memory fake {@link DHTxxConn} for unit tests — no hardware, no GPIO.
 *
 * <p>DHTxx has no register map and no framing to fake at this level: the
 * chip driver only ever calls {@link #read()} and gets back a raw 5-byte
 * frame, or an exception propagating straight through (e.g. a real
 * connection's timeout/framing {@code DHTxxConnectionException}). Preload
 * frames with {@link #queueRead}; preload an exception to throw instead
 * with {@link #queueThrow}. Each {@link #read()} call pops the next queued
 * item (falling back to a 5 zero-byte frame if the queue is empty, matching
 * the real connection's disabled-state return value).
 */
public class MockDHTxxConnection implements DHTxxConn {

    private final Deque<Object> queue = new ArrayDeque<>();

    /** Queue a raw 5-byte frame to be returned by the next {@link #read()}. */
    public void queueRead(byte[] frame) {
        queue.addLast(frame.clone());
    }

    /** Queue an {@link IOException} to be thrown by the next {@link #read()}. */
    public void queueThrow(IOException e) {
        queue.addLast(e);
    }

    @Override
    public byte[] read() throws IOException {
        if (queue.isEmpty()) {
            return new byte[5];
        }
        Object item = queue.pollFirst();
        if (item instanceof IOException) {
            throw (IOException) item;
        }
        return (byte[]) item;
    }
}
