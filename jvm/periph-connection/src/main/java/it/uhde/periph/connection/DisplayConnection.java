package it.uhde.periph.connection;

import java.io.IOException;
import java.io.InterruptedIOException;

/**
 * Display connection (SPI + D/C#, RES#, BUSY) for the JVM.
 *
 * <p>Bundles an SPI link to a display controller with its side-band lines so
 * chip drivers call {@link #sendCommand}, {@link #sendData}, {@link #readData},
 * {@link #reset} and {@link #waitBusy} and never touch D/C, RES, BUSY or CS.
 * RES# and BUSY are optional ({@code null}); an OLED has no BUSY line and may
 * tie RES# high. See {@code specs/transport_display.md}.
 *
 * <p>CS is hardware CS only: every {@link #sendCommand} / {@link #sendData}
 * transfer and every byte of {@link #readData} is its own CS window.
 *
 * <p>Two ways to reach the controller: {@link #hardware} opens {@code
 * /dev/spidevB.D} (kernel-owned CS), {@link #software} bit-bangs the bus over
 * {@link OutputPin}/{@link InputPin} lines and drives CS itself.
 */
public final class DisplayConnection implements DisplayConn {

    /** One CS window per call; the transfer primitive underneath. */
    public interface Transport extends AutoCloseable {
        /** Send {@code data} in one CS window. */
        void write(byte[] data) throws IOException;

        /** Clock in {@code n} bytes in one CS window. */
        byte[] read(int n) throws IOException;

        @Override
        void close() throws IOException;
    }

    private final Transport spi;
    private final OutputPin dc;
    private final OutputPin res;   // null if not wired
    private final InputPin busy;   // null if not wired
    private final OutputPin enPin; // null if not wired
    private final WireMode wireMode;
    private volatile boolean enabled = true;
    private volatile boolean enActiveHigh = true;
    private int resetPulseUs = 200;
    private int resetSettleMs = 10;

    /**
     * Build a connection over any {@link Transport} (used by the factories and
     * by tests).
     *
     * @param spi      transfer primitive
     * @param dc       D/C# pin (low = command, high = data)
     * @param res      RES# pin, or {@code null}
     * @param busy     BUSY pin, or {@code null}
     * @param enPin    optional EN pin, or {@code null}
     * @param wireMode wire mode; {@link WireMode#HALF_DUPLEX_HW} is rejected
     */
    public DisplayConnection(Transport spi, OutputPin dc, OutputPin res, InputPin busy,
                             OutputPin enPin, WireMode wireMode) {
        if (dc == null) throw new IllegalArgumentException("dc pin is required");
        if (wireMode == WireMode.HALF_DUPLEX_HW) {
            throw new UnsupportedOperationException(
                "HALF_DUPLEX_HW is not supported on the JVM; use HALF_DUPLEX_PIN");
        }
        this.spi = spi;
        this.dc = dc;
        this.res = res;
        this.busy = busy;
        this.enPin = enPin;
        this.wireMode = wireMode;
        dc.set(false);
        if (res != null) res.set(true);
    }

    /**
     * Hardware SPI: opens {@code /dev/spidevBUS.DEVICE}, mode 0, MSB first.
     *
     * @param busNumber    SPI bus number
     * @param deviceNumber chip-select line on the bus
     * @param maxSpeedHz   SPI clock in Hz
     * @param dc           D/C# pin
     * @param res          RES# pin, or {@code null}
     * @param busy         BUSY pin, or {@code null}
     * @param wireMode     {@link WireMode#SIMPLEX} or {@link WireMode#HALF_DUPLEX_PIN}
     * @return a new connection
     * @throws IOException if the spidev device cannot be opened
     */
    public static DisplayConnection hardware(int busNumber, int deviceNumber, int maxSpeedHz,
                                             OutputPin dc, OutputPin res, InputPin busy,
                                             WireMode wireMode) throws IOException {
        if (wireMode == WireMode.HALF_DUPLEX_HW) {
            throw new UnsupportedOperationException(
                "HALF_DUPLEX_HW is not supported on the JVM; use HALF_DUPLEX_PIN");
        }
        SPIConnection spi = new SPIConnection(busNumber, deviceNumber, 0, maxSpeedHz);
        Transport t = new Transport() {
            @Override public void write(byte[] data) throws IOException { spi.write(data); }
            @Override public byte[] read(int n) throws IOException { return spi.read(n); }
            @Override public void close() throws IOException { spi.close(); }
        };
        try {
            return new DisplayConnection(t, dc, res, busy, null, wireMode);
        } catch (RuntimeException e) {
            spi.close();
            throw e;
        }
    }

    /**
     * Software SPI: bit-bangs SCK/MOSI (and MISO for {@link
     * WireMode#HALF_DUPLEX_PIN}) and drives CS (active low) around each
     * transfer.
     *
     * @param sck      clock output
     * @param mosi     data output
     * @param miso     data input, required for {@link WireMode#HALF_DUPLEX_PIN}; may be {@code null} otherwise
     * @param cs       CS# output (active low)
     * @param dc       D/C# pin
     * @param res      RES# pin, or {@code null}
     * @param busy     BUSY pin, or {@code null}
     * @param wireMode {@link WireMode#SIMPLEX} or {@link WireMode#HALF_DUPLEX_PIN}
     * @return a new connection
     */
    public static DisplayConnection software(OutputPin sck, OutputPin mosi, InputPin miso,
                                             OutputPin cs, OutputPin dc, OutputPin res,
                                             InputPin busy, WireMode wireMode) {
        if (cs == null) throw new IllegalArgumentException("software SPI needs a cs pin");
        if (wireMode == WireMode.HALF_DUPLEX_PIN && miso == null) {
            throw new IllegalArgumentException("HALF_DUPLEX_PIN needs a miso pin");
        }
        SoftSpi bus = new SoftSpi(sck, mosi, miso);
        cs.set(true);
        Transport t = new Transport() {
            @Override public void write(byte[] data) {
                cs.set(false);
                try { bus.write(data); } finally { cs.set(true); }
            }
            @Override public byte[] read(int n) throws IOException {
                cs.set(false);
                try { return bus.read(n, 0x00); } finally { cs.set(true); }
            }
            @Override public void close() {}
        };
        return new DisplayConnection(t, dc, res, busy, null, wireMode);
    }

    /**
     * Override the reset timing (defaults: 200&nbsp;&micro;s pulse, 10&nbsp;ms settle).
     *
     * @param pulseUs  RES# low time in microseconds
     * @param settleMs wait after RES# release in milliseconds
     * @return this connection, for chaining
     */
    public DisplayConnection withResetTiming(int pulseUs, int settleMs) {
        this.resetPulseUs = pulseUs;
        this.resetSettleMs = settleMs;
        return this;
    }

    /**
     * Select the EN-pin polarity (default active-high).
     *
     * @param activeHigh {@code true} if the EN pin is active-high
     * @return this connection, for chaining
     */
    public DisplayConnection setEnActiveHigh(boolean activeHigh) {
        this.enActiveHigh = activeHigh;
        return this;
    }

    @Override public void enable()  { enabled = true;  if (enPin != null) enPin.set(enActiveHigh); }
    @Override public void disable() { enabled = false; if (enPin != null) enPin.set(!enActiveHigh); }
    @Override public boolean isEnabled() { return enabled; }

    @Override
    public void reset() throws IOException {
        if (!enabled || res == null) return;
        res.set(false);
        long end = System.nanoTime() + resetPulseUs * 1000L;
        while (System.nanoTime() < end) { /* spin: sub-ms pulse */ }
        res.set(true);
        sleepMs(resetSettleMs);
    }

    @Override
    public boolean busyLevel() throws IOException {
        return busy != null && busy.isHigh();
    }

    @Override
    public boolean waitBusy(boolean busyActiveHigh, long timeoutMs) throws IOException {
        if (busy == null) return true;
        long start = System.nanoTime();
        while (busy.isHigh() == busyActiveHigh) {
            if (timeoutMs > 0 && (System.nanoTime() - start) / 1_000_000L >= timeoutMs) return false;
            sleepMs(1);
        }
        return true;
    }

    @Override
    public void sendCommand(int cmd, byte[] data) throws IOException {
        if (!enabled) return;
        dc.set(false);
        spi.write(new byte[] {(byte) cmd});
        if (data != null && data.length > 0) {
            dc.set(true);
            spi.write(data);
        }
    }

    @Override
    public void sendData(byte[] data) throws IOException {
        if (!enabled || data == null || data.length == 0) return;
        dc.set(true);
        spi.write(data);
    }

    @Override
    public byte[] readData(int length) throws IOException {
        if (wireMode == WireMode.SIMPLEX) {
            throw new UnsupportedOperationException("readData() is not available in SIMPLEX wire mode");
        }
        if (!enabled) return new byte[length];
        dc.set(true);
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) out[i] = spi.read(1)[0];
        return out;
    }

    @Override
    public void close() throws IOException {
        spi.close();
    }

    private static void sleepMs(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted");
        }
    }
}
