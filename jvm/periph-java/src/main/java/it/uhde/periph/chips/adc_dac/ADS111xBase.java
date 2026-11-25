package it.uhde.periph.chips.adc_dac;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * Shared implementation of the ADS1113 / ADS1114 / ADS1115 16-bit ΔΣ ADCs.
 *
 * <p>The three chips share one register map and one Config-register layout and differ only in
 * which Config fields the silicon honours (ADS1113: fixed ±2.048&nbsp;V, no comparator, no mux;
 * ADS1114: PGA + comparator; ADS1115: PGA + comparator + 4-input mux). This base holds the
 * whole-word Config builder, the single-shot sequence and every feature as protected members; the
 * per-chip classes publish exactly the subset their silicon supports.
 *
 * <p>Registers are 16-bit big-endian behind a write-only Address Pointer. Every Config write is a
 * whole word — never read-modify-write.
 */
public abstract class ADS111xBase {

    /** Default 7-bit I²C address ({@code ADDR} = GND). Valid range 0x48–0x4B. */
    public static final int DEFAULT_ADDRESS = 0x48;

    /** Interrupt source: a conversion finished ({@code ALERT/RDY} in conversion-ready mode). */
    public static final int SOURCE_CONVERSION_READY = 0x01;
    /** Interrupt source: result beyond the comparator thresholds. */
    public static final int SOURCE_COMPARATOR = 0x02;

    protected static final int REG_CONVERSION = 0x00;
    protected static final int REG_CONFIG     = 0x01;
    protected static final int REG_LO_THRESH  = 0x02;
    protected static final int REG_HI_THRESH  = 0x03;

    protected static final int CONFIG_RESET = 0x8583;
    protected static final int LO_RESET     = 0x8000;
    protected static final int HI_RESET     = 0x7FFF;
    protected static final int CFG_OS       = 0x8000;
    protected static final int CFG_MODE_SHOT = 0x0100;
    protected static final int QUE_DISABLED = 3;

    /** Full-scale range in volts, indexed by {@code PGA[2:0]}. */
    protected static final double[] FSR = {6.144, 4.096, 2.048, 1.024, 0.512, 0.256};
    /** Samples per second, indexed by {@code DR[2:0]}. */
    protected static final int[] RATES = {8, 16, 32, 64, 128, 250, 475, 860};

    protected final RegisterConnection connection;

    protected int mux = 0;
    protected int pga = 2;
    protected int dr = 4;
    protected int compMode = 0;
    protected int compPol = 0;
    protected int compLat = 0;
    protected int compQue = QUE_DISABLED;
    protected int queue = 0;
    protected boolean continuous = false;
    protected int loRaw = -32768;
    protected int hiRaw = 32767;
    protected int source = 0;

    private volatile IntConsumer callback;
    private InputPin intPin;
    private volatile boolean polling = false;
    private Thread pollThread;
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * Wrap a register connection; makes no bus traffic.
     *
     * @param connection I²C or SMBus register connection (0x48–0x4B)
     */
    protected ADS111xBase(RegisterConnection connection) {
        this.connection = connection;
    }

    /** Read a 16-bit big-endian register. */
    protected int readReg(int reg) throws IOException {
        byte[] b = connection.read(reg, 2);
        return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
    }

    /** Write a 16-bit big-endian register. */
    protected void writeReg(int reg, int value) throws IOException {
        connection.write(reg, new byte[]{(byte) (value >> 8), (byte) value});
    }

    private int configWord(boolean osBit, int muxCode) {
        return (osBit ? CFG_OS : 0) | (muxCode << 12) | (pga << 9) | (continuous ? 0 : CFG_MODE_SHOT)
                | (dr << 5) | (compMode << 4) | (compPol << 3) | (compLat << 2) | compQue;
    }

    /** Write the whole Config word with the cached fields. */
    protected void writeConfig(boolean osBit) throws IOException {
        writeReg(REG_CONFIG, configWord(osBit, mux));
    }

    /**
     * Single-shot conversion on {@code muxCode}.
     *
     * @return signed 16-bit code
     * @throws IOException on bus error or if {@code OS} never returns to 1
     */
    protected int convert(int muxCode) throws IOException {
        continuous = false;
        writeReg(REG_CONFIG, configWord(true, muxCode));
        long wait = 1000L / RATES[dr] + 1;
        long deadline = System.currentTimeMillis() + 2 * wait + 50;
        sleep(wait);
        while ((readReg(REG_CONFIG) & CFG_OS) == 0) {
            if (System.currentTimeMillis() > deadline) throw new IOException("ADS111x conversion timeout");
            sleep(1);
        }
        return (short) readReg(REG_CONVERSION);
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    /** Set the data rate ({@code DR[2:0]}, 0–7). */
    protected void doSetDataRate(int rate) throws IOException {
        if (rate < 0 || rate > 7) throw new IllegalArgumentException("dr must be 0-7");
        dr = rate;
        if (continuous) writeConfig(false);
    }

    /** Set the PGA range ({@code PGA[2:0]}, 0–5). */
    protected void doSetGain(int code) throws IOException {
        if (code < 0 || code > 5) throw new IllegalArgumentException("pga must be 0-5");
        pga = code;
        if (continuous) writeConfig(false);
    }

    /** Start continuous conversion on {@code muxCode} (0–7). */
    protected void doStartContinuous(int muxCode) throws IOException {
        if (muxCode < 0 || muxCode > 7) throw new IllegalArgumentException("mux must be 0-7");
        mux = muxCode;
        continuous = true;
        writeConfig(false);
    }

    /** Read CONVERSION without touching Config. */
    protected int doReadContinuousRaw() throws IOException {
        return (short) readReg(REG_CONVERSION);
    }

    /** Return to single-shot / power-down mode. */
    protected void doStopContinuous() throws IOException {
        continuous = false;
        writeConfig(false);
    }

    /** {@code true} while {@code CONFIG.OS} reads 0. */
    protected boolean doIsConverting() throws IOException {
        return (readReg(REG_CONFIG) & CFG_OS) == 0;
    }

    /** ±FSR of the current gain in volts. */
    protected double doFullScale() {
        return FSR[pga];
    }

    /** Size of one code in volts. */
    protected double doLsb() {
        return FSR[pga] / 32768.0;
    }

    /** Write the reset values back and clear the cached settings. */
    protected void doReset(boolean withThresholds) throws IOException {
        writeReg(REG_CONFIG, CONFIG_RESET);
        if (withThresholds) {
            writeReg(REG_LO_THRESH, LO_RESET);
            writeReg(REG_HI_THRESH, HI_RESET);
        }
        mux = 0; pga = 2; dr = 4;
        compMode = 0; compPol = 0; compLat = 0; compQue = QUE_DISABLED;
        queue = 0; continuous = false; loRaw = -32768; hiRaw = 32767; source = 0;
    }

    /** Configure the comparator and route it to {@code ALERT/RDY}. */
    protected void doConfigureComparator(int mode, int polarity, boolean latch, int q) throws IOException {
        if (mode < 0 || mode > 1 || polarity < 0 || polarity > 1 || q < 0 || q > 2) {
            throw new IllegalArgumentException("invalid comparator argument");
        }
        if (hiRaw <= loRaw) throw new IllegalArgumentException("high threshold must exceed low threshold");
        compMode = mode;
        compPol = polarity;
        compLat = latch ? 1 : 0;
        queue = q;
        compQue = q;
        source = SOURCE_COMPARATOR;
        writeConfig(false);
    }

    /** Set {@code COMP_QUE} to 11. */
    protected void doDisableComparator() throws IOException {
        compQue = QUE_DISABLED;
        source = 0;
        writeConfig(false);
    }

    /** Write LO_THRESH. */
    protected void doSetLowThresholdRaw(int raw) throws IOException {
        loRaw = raw;
        writeReg(REG_LO_THRESH, raw & 0xFFFF);
    }

    /** Write HI_THRESH. */
    protected void doSetHighThresholdRaw(int raw) throws IOException {
        hiRaw = raw;
        writeReg(REG_HI_THRESH, raw & 0xFFFF);
    }

    private int voltsToCode(double volts) {
        double steps = volts / doLsb();
        long code = steps >= 0 ? (long) (steps + 0.5) : -(long) (-steps + 0.5);
        return (int) Math.max(-32768, Math.min(32767, code));
    }

    /** Write both thresholds from volts at the current gain. */
    protected void doSetThresholds(double low, double high) throws IOException {
        if (high <= low) throw new IllegalArgumentException("high must exceed low");
        doSetLowThresholdRaw(voltsToCode(low));
        doSetHighThresholdRaw(voltsToCode(high));
    }

    /** Read both thresholds back, sign-extended. */
    protected int[] doGetThresholdsRaw() throws IOException {
        return new int[]{(short) readReg(REG_LO_THRESH), (short) readReg(REG_HI_THRESH)};
    }

    /** Read CONVERSION to release a latched comparator output. */
    protected void doClearAlert() throws IOException {
        readReg(REG_CONVERSION);
    }

    /** Route one source to {@code ALERT/RDY}. */
    protected void doEnableInterrupt(int src) throws IOException {
        if (src == SOURCE_CONVERSION_READY) {
            writeReg(REG_HI_THRESH, 0x8000);
            writeReg(REG_LO_THRESH, 0x0000);
            compQue = 0;
            source = SOURCE_CONVERSION_READY;
            writeConfig(false);
        } else if (src == SOURCE_COMPARATOR) {
            doSetLowThresholdRaw(loRaw);
            doSetHighThresholdRaw(hiRaw);
            doConfigureComparator(compMode, compPol, compLat == 1, queue);
        } else {
            throw new IllegalArgumentException("unknown interrupt source");
        }
    }

    /** The enabled source if {@code ALERT/RDY} is asserted, else 0. */
    protected int doPollInterrupt() throws IOException {
        if (source == 0) return 0;
        InputPin pin = connection.intPin();
        if (pin == null) pin = intPin;
        boolean asserted;
        if (pin != null) asserted = pin.isHigh() == (compPol == 1);
        else if (source == SOURCE_CONVERSION_READY) asserted = (readReg(REG_CONFIG) & CFG_OS) != 0;
        else asserted = false;
        if (!asserted) return 0;
        if (source == SOURCE_COMPARATOR && compLat == 1) doClearAlert();
        return source;
    }

    /** Subscribe to {@code ALERT/RDY} events via {@code pin} (or a 5 ms polling thread if null). */
    protected void doOnInterrupt(IntConsumer cb, InputPin pin) throws IOException {
        doOffInterrupt();
        callback = cb;
        if (pin != null) {
            intPin = pin;
            pin.onEdge(edgeHandler, compPol == 1 ? EdgeTrigger.RISING : EdgeTrigger.FALLING);
        } else {
            startPolling(doPollInterrupt());
        }
    }

    /** Unsubscribe and stop delivery. */
    protected void doOffInterrupt() {
        callback = null;
        if (intPin != null) {
            intPin.offEdge(edgeHandler);
            intPin = null;
        }
        polling = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    private void startPolling(int initialStatus) {
        polling = true;
        pollThread = new Thread(() -> {
            int last = initialStatus;
            while (polling) {
                try {
                    int status = doPollInterrupt();
                    IntConsumer cb = callback;
                    if (status != last && cb != null) cb.accept(status);
                    last = status;
                } catch (IOException ignored) {
                    // bus error; retry on the next tick
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "ads111x-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void handleEdge() {
        try {
            int status = doPollInterrupt();
            IntConsumer cb = callback;
            if (cb != null) cb.accept(status);
        } catch (IOException ignored) {
            // bus error; wait for the next edge rather than propagating
        }
    }
}
