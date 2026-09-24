package it.uhde.periph.chips.tof;

import it.uhde.periph.connection.Connection;
import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * Shared base for ST's VL53 FlightSense Time-of-Flight ranging family.
 *
 * <p>Internal — never instantiated directly. VL53L0X and VL53L1X have different register maps
 * (8-bit vs 16-bit register index), so this base holds no register addresses and no ranging
 * logic, only the plumbing both chips share: big-endian register access with a 1- or 2-byte
 * index, the bounded poll helper, the XSHUT boot wait, interrupt delivery (IntPin edge or 5&nbsp;ms
 * polling thread), the volatile re-addressing helper, and the family's shared constants. Chip
 * drivers serialize multi-register sequences with {@code synchronized} on the instance. See
 * {@code specs/tof/_vl53_base.md}.
 *
 * <p>Used by the Java drivers and — via normal JVM inheritance — by the Kotlin drivers.
 */
public abstract class VL53Base {

    /** Power-on 7-bit I²C address of every family member. */
    public static final int I2C_ADDRESS = 0x29;
    /** Interrupt source: range &lt; low threshold. */
    public static final int SOURCE_LEVEL_LOW = 1;
    /** Interrupt source: range &gt; high threshold. */
    public static final int SOURCE_LEVEL_HIGH = 2;
    /** Interrupt source: range &lt; low threshold or &gt; high threshold. */
    public static final int SOURCE_OUT_OF_WINDOW = 3;
    /** Interrupt source: a new measurement is available (driver default). */
    public static final int SOURCE_NEW_SAMPLE_READY = 4;
    /** Interrupt source: low &le; range &le; high (VL53L1X only). */
    public static final int SOURCE_IN_WINDOW = 5;

    /** Poll timeout in ms. */
    protected static final int TIMEOUT_MS = 500;
    /** XSHUT-high → first I²C access; tBOOT ≤ 1.2&nbsp;ms rounded up to the sleep resolution. */
    protected static final int BOOT_MS = 2;

    /** Condition polled by {@link #waitUntil(Condition, String)}. */
    @FunctionalInterface
    protected interface Condition {
        /**
         * @return true once the awaited state is reached
         * @throws IOException on bus error
         */
        boolean test() throws IOException;
    }

    /** Configured I²C connection pointing at the device. */
    protected final Connection connection;
    private final int indexBytes;
    private final String chipName;

    private volatile IntConsumer callback;
    private InputPin subscribedPin;
    private volatile boolean polling = false;
    private Thread pollThread;
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * @param connection configured I²C connection pointing at the device
     * @param indexBytes register index width on the wire, 1 or 2
     * @param chipName   chip name used in error messages and the polling thread name
     */
    protected VL53Base(Connection connection, int indexBytes, String chipName) {
        this.connection = connection;
        this.indexBytes = indexBytes;
        this.chipName = chipName;
    }

    private byte[] frame(int reg, int dataLen) {
        byte[] buf = new byte[indexBytes + dataLen];
        if (indexBytes == 2) {
            buf[0] = (byte) (reg >> 8);
            buf[1] = (byte) reg;
        } else {
            buf[0] = (byte) reg;
        }
        return buf;
    }

    /**
     * Write consecutive registers starting at {@code reg}.
     *
     * @param reg  register index
     * @param data bytes to write
     * @throws IOException on bus error
     */
    protected void writeBlock(int reg, byte[] data) throws IOException {
        byte[] buf = frame(reg, data.length);
        System.arraycopy(data, 0, buf, indexBytes, data.length);
        connection.write(buf);
    }

    /**
     * Read {@code n} consecutive registers starting at {@code reg}.
     *
     * @param reg register index
     * @param n   number of bytes
     * @return the bytes read
     * @throws IOException on bus error
     */
    protected byte[] readBlock(int reg, int n) throws IOException {
        return connection.writeRead(frame(reg, 0), n);
    }

    /** @param reg register index @param value byte value @throws IOException on bus error */
    protected void write8(int reg, int value) throws IOException {
        writeBlock(reg, new byte[]{(byte) value});
    }

    /** @param reg register index @return unsigned byte value @throws IOException on bus error */
    protected int read8(int reg) throws IOException {
        return readBlock(reg, 1)[0] & 0xFF;
    }

    /** @param reg register index @param value big-endian 16-bit value @throws IOException on bus error */
    protected void write16(int reg, int value) throws IOException {
        writeBlock(reg, new byte[]{(byte) (value >> 8), (byte) value});
    }

    /** @param reg register index @return unsigned big-endian 16-bit value @throws IOException on bus error */
    protected int read16(int reg) throws IOException {
        byte[] b = readBlock(reg, 2);
        return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
    }

    /** @param reg register index @param value big-endian 32-bit value @throws IOException on bus error */
    protected void write32(int reg, long value) throws IOException {
        writeBlock(reg, new byte[]{(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value});
    }

    /** @param reg register index @return unsigned big-endian 32-bit value @throws IOException on bus error */
    protected long read32(int reg) throws IOException {
        byte[] b = readBlock(reg, 4);
        return ((long) (b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    /**
     * Poll {@code condition} until it holds.
     *
     * @param condition awaited state
     * @param what      description for the timeout message
     * @throws IOException on bus error, or "&lt;chip&gt; timeout waiting for &lt;what&gt;" after 500&nbsp;ms
     */
    protected void waitUntil(Condition condition, String what) throws IOException {
        long start = System.nanoTime();
        while (true) {
            if (condition.test()) return;
            if ((System.nanoTime() - start) / 1_000_000 > TIMEOUT_MS) {
                throw new IOException(chipName + " timeout waiting for " + what);
            }
        }
    }

    /** Drive XSHUT high (if the connection has an {@code enPin}) and wait tBOOT. */
    protected void bootWait() {
        if (connection.enPin() != null) connection.enable();
        try {
            Thread.sleep(BOOT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Write a new 7-bit I²C address to {@code reg}.
     *
     * @param reg     address register index
     * @param address new address, 0x08–0x77
     * @throws IllegalArgumentException if out of range (no write)
     * @throws IOException              on bus error
     */
    protected void setAddressReg(int reg, int address) throws IOException {
        if (address < 0x08 || address > 0x77) throw new IllegalArgumentException("address must be 0x08 to 0x77");
        write8(reg, address & 0x7F);
    }

    /**
     * Read and clear a pending interrupt.
     *
     * @return the active {@code SOURCE_*} value, or 0 if nothing is pending
     * @throws IOException on bus error
     */
    protected abstract int pollInterruptStatus() throws IOException;

    /**
     * Deliver GPIO1 events (falling edge, active low) to {@code callback}; a 5&nbsp;ms polling
     * thread is used when {@code intPin} is null.
     *
     * @param callback receives the {@code SOURCE_*} value
     * @param intPin   pin wired to GPIO1, or null
     */
    protected void subscribe(IntConsumer callback, InputPin intPin) {
        unsubscribe();
        this.callback = callback;
        if (intPin != null) {
            subscribedPin = intPin;
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING);
        } else {
            startPolling();
        }
    }

    /** Detach the edge handler or stop the polling thread, and clear the callback. */
    protected void unsubscribe() {
        callback = null;
        if (subscribedPin != null) {
            subscribedPin.offEdge(edgeHandler);
            subscribedPin = null;
        }
        polling = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    private void startPolling() {
        polling = true;
        pollThread = new Thread(() -> {
            while (polling) {
                handleEdge();
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, chipName.toLowerCase() + "-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void handleEdge() {
        try {
            int status = pollInterruptStatus();
            IntConsumer cb = callback;
            if (status != 0 && cb != null) cb.accept(status);
        } catch (IOException ignored) {
            // bus error; wait for the next edge rather than propagating
        }
    }
}
