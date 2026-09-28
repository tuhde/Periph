package it.uhde.periph.connection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory fake {@link SiPo} for unit tests — no hardware, no bus.
 *
 * <p>Models the write-only shift-register protocol chip drivers in this repo
 * use (TPIC6B595, SN74HC595, ...): {@link #write} shifts + latches,
 * {@link #clear} pulses SRCLR, {@link #setOutputEnable} drives G. SRCLR/G
 * availability is configurable at construction, matching
 * {@link SiPoConnection}'s thrown {@link IllegalStateException} when the
 * corresponding GPIO line wasn't wired.
 */
public class MockSiPo implements SiPo {

    private final List<byte[]> writes = new ArrayList<>();
    private final List<Boolean> outputEnableCalls = new ArrayList<>();
    private int clearCount = 0;
    private final boolean hasSrclr;
    private final boolean hasG;
    private boolean enabled = true;

    public MockSiPo() { this(true, true); }

    public MockSiPo(boolean hasSrclr, boolean hasG) {
        this.hasSrclr = hasSrclr;
        this.hasG = hasG;
    }

    @Override public void enable() { enabled = true; }
    @Override public void disable() { enabled = false; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public InputPin intPin() { return null; }
    @Override public OutputPin enPin() { return null; }

    @Override
    public void write(byte[] data) {
        if (!enabled) return;
        writes.add(data.clone());
    }

    @Override
    public byte[] read(int n) {
        throw new UnsupportedOperationException("SiPo is write-only");
    }

    @Override
    public byte[] writeRead(byte[] data, int n) {
        throw new UnsupportedOperationException("SiPo is write-only");
    }

    @Override
    public void clear() throws IOException {
        if (!hasSrclr) throw new IllegalStateException("SRCLR not configured");
        clearCount++;
    }

    @Override
    public void setOutputEnable(boolean enabled) throws IOException {
        if (!hasG) throw new IllegalStateException("G not configured");
        outputEnableCalls.add(enabled);
    }

    @Override
    public void close() {}

    /** Log of every {@link #write} call, in call order. */
    public List<byte[]> writes() { return writes; }

    /** Number of successful {@link #clear} calls. */
    public int clearCount() { return clearCount; }

    /** Log of every successful {@link #setOutputEnable} argument, in call order. */
    public List<Boolean> outputEnableCalls() { return outputEnableCalls; }
}
