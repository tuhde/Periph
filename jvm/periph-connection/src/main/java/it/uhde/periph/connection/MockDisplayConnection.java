package it.uhde.periph.connection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * In-memory fake {@link DisplayConn} for unit tests — no hardware, no bus.
 *
 * <p>Records every call in {@link #events()} in order, as strings:
 * {@code reset}, {@code command:<hex>:<hex bytes>}, {@code data:<hex bytes>},
 * {@code read:<n>}, {@code waitBusy:<activeHigh>:<timeoutMs>}.
 */
public class MockDisplayConnection implements DisplayConn {

    private final List<String> events = new ArrayList<>();
    private final List<int[]> commands = new ArrayList<>();
    private final List<byte[]> commandData = new ArrayList<>();
    private final List<Byte> readQueue = new ArrayList<>();
    private final WireMode wireMode;
    private boolean enabled = true;
    private boolean ready = true;
    private boolean busyHigh = false;

    public MockDisplayConnection() { this(WireMode.SIMPLEX); }

    public MockDisplayConnection(WireMode wireMode) { this.wireMode = wireMode; }

    /** Set the {@link #waitBusy} result; {@code false} simulates a timeout. */
    public void setReady(boolean ready) { this.ready = ready; }

    /** Set the raw level {@link #busyLevel} returns. */
    public void setBusyHigh(boolean busyHigh) { this.busyHigh = busyHigh; }

    /** Queue bytes that later {@link #readData} calls return, in order. */
    public void queueRead(byte... data) { for (byte b : data) readQueue.add(b); }

    /** @return every recorded call, in order */
    public List<String> events() { return events; }

    /** @return the command bytes sent, in order */
    public List<Integer> commands() {
        List<Integer> out = new ArrayList<>();
        for (int[] c : commands) out.add(c[0]);
        return out;
    }

    /** @return the parameter bytes of the {@code index}-th command */
    public byte[] commandData(int index) { return commandData.get(index); }

    @Override public void enable()  { enabled = true; }
    @Override public void disable() { enabled = false; }
    @Override public boolean isEnabled() { return enabled; }

    @Override public void reset() { if (enabled) events.add("reset"); }
    @Override public boolean busyLevel() { return busyHigh; }

    @Override
    public boolean waitBusy(boolean busyActiveHigh, long timeoutMs) {
        events.add("waitBusy:" + busyActiveHigh + ":" + timeoutMs);
        return ready;
    }

    @Override
    public void sendCommand(int cmd, byte[] data) {
        if (!enabled) return;
        byte[] copy = data == null ? new byte[0] : data.clone();
        commands.add(new int[] {cmd & 0xFF});
        commandData.add(copy);
        events.add(String.format("command:%02x:%s", cmd & 0xFF, hex(copy)));
    }

    @Override
    public void sendData(byte[] data) {
        if (!enabled) return;
        events.add("data:" + hex(data));
    }

    @Override
    public byte[] readData(int length) {
        if (wireMode == WireMode.SIMPLEX) {
            throw new UnsupportedOperationException("readData() is not available in SIMPLEX wire mode");
        }
        byte[] out = new byte[length];
        if (!enabled) return out;
        events.add("read:" + length);
        for (int i = 0; i < length && !readQueue.isEmpty(); i++) out[i] = readQueue.remove(0);
        return out;
    }

    @Override public void close() {}

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }
}
