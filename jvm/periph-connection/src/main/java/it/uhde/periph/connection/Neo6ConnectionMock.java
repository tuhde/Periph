package it.uhde.periph.connection;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * In-memory fake {@link Connection} for NEO-6 unit tests — no hardware, no bus.
 *
 * <p>NEO-6's driver treats UART and I2C (DDC) as the same underlying NMEA/UBX
 * byte stream (see {@code specs/gnss/neo-6.md}'s "Connection abstraction"
 * note): {@code read(1)} for UART, {@code writeRead(new byte[]{(byte) 0xFF},
 * 1)} for I2C. This mock is transport-shape agnostic: preload the stream
 * with {@link #queueBytes}; {@link #read} and {@link #writeRead} both just
 * pop the next byte(s) off the front of one shared FIFO, regardless of the
 * prefix passed to {@code writeRead} — matching the real module, where both
 * transports deliver the same bytes and only the framing differs. {@link
 * #write} calls (e.g. {@code sendUbx}) are logged to {@link #writes()} for
 * assertions.
 *
 * <p>Also implements {@link AvailableConnection} so it is usable for the
 * UART bus-type path, which gates {@code read()} behind {@code available()
 * > 0} (see {@code Neo6Minimal.readByte()}); {@link #available()} reports
 * the real queue depth so that path stops cleanly once the queue is
 * drained instead of blocking or fabricating zero bytes.
 */
public class Neo6ConnectionMock implements Connection, AvailableConnection {

    private final Deque<Byte> stream = new ArrayDeque<>();
    private final List<byte[]> writes = new ArrayList<>();

    /** Append bytes to the end of the shared read stream. */
    public void queueBytes(byte[] data) {
        for (byte b : data) {
            stream.addLast(b);
        }
    }

    /** Log of every {@link #write} call, in call order. */
    public List<byte[]> writes() {
        return writes;
    }

    @Override
    public int available() {
        return stream.size();
    }

    @Override
    public void enable() {
        // no-op: this mock has no gate to toggle
    }

    @Override
    public void disable() {
        // no-op: this mock has no gate to toggle
    }

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
    }

    @Override
    public byte[] read(int n) throws IOException {
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            Byte b = stream.pollFirst();
            out[i] = b != null ? b : 0;
        }
        return out;
    }

    @Override
    public byte[] writeRead(byte[] data, int n) throws IOException {
        return read(n);
    }

    @Override
    public void close() throws IOException {
        // no-op
    }
}
