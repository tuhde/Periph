package it.uhde.periph.connection;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory fake {@link Connection} for unit tests — no hardware, no bus.
 *
 * <p>Supports the two access patterns chip drivers in this repo use:
 * <ul>
 *   <li>Register-addressed reads ({@code writeRead(new byte[]{reg}, n)}): backed
 *       by a byte-addressable register map. Preload it with {@link #setRegister}
 *       before constructing the chip.</li>
 *   <li>Plain streamed reads ({@code read(n)}, no register address): backed by a
 *       FIFO queue. Preload responses with {@link #queueRead}; each {@code read(n)}
 *       call pops the next one. Falls back to {@code n} zero bytes if the queue is
 *       empty.</li>
 * </ul>
 *
 * <p>Every {@code write()} call (register writes and plain command writes alike)
 * is appended to {@link #writes()} for assertions, and 2+ byte writes are also
 * applied to the register map so a later {@code writeRead} sees them.
 */
public class MockConnection implements Connection {

    private final Map<Integer, Integer> registers = new HashMap<>();
    private final List<byte[]> writes = new ArrayList<>();
    private final Deque<byte[]> readQueue = new ArrayDeque<>();

    /** Preload consecutive register bytes starting at {@code reg}. */
    public void setRegister(int reg, int... values) {
        for (int i = 0; i < values.length; i++) {
            registers.put(reg + i, values[i] & 0xFF);
        }
    }

    /** Queue bytes to be returned by the next plain {@code read(n)} call. */
    public void queueRead(byte[] data) {
        readQueue.addLast(data);
    }

    /** Log of every write()/write_read() write phase, in call order. */
    public List<byte[]> writes() {
        return writes;
    }

    /** Direct access to the register map for assertions. */
    public Map<Integer, Integer> registers() {
        return registers;
    }

    @Override
    public void enable() {}

    @Override
    public void disable() {}

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public InputPin intPin() {
        return null;
    }

    @Override
    public OutputPin enPin() {
        return null;
    }

    @Override
    public void write(byte[] data) throws IOException {
        writes.add(data.clone());
        if (data.length >= 2) {
            int reg = data[0] & 0xFF;
            for (int i = 1; i < data.length; i++) {
                registers.put(reg + i - 1, data[i] & 0xFF);
            }
        }
    }

    @Override
    public byte[] read(int n) throws IOException {
        if (!readQueue.isEmpty()) {
            byte[] front = readQueue.pollFirst();
            byte[] out = new byte[n];
            System.arraycopy(front, 0, out, 0, Math.min(n, front.length));
            return out;
        }
        return new byte[n];
    }

    @Override
    public byte[] writeRead(byte[] data, int n) throws IOException {
        writes.add(data.clone());
        int reg = data[0] & 0xFF;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) (int) registers.getOrDefault(reg + i, 0);
        }
        return out;
    }

    @Override
    public void close() throws IOException {}
}
