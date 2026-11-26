package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * SPL06-007 full driver — extends {@link Spl06Minimal} with oversampling and rate configuration,
 * background (continuous) mode, the 32-entry FIFO, raw values and compensation, altitude, soft
 * reset and the data-ready / FIFO-full interrupt.
 *
 * <p>The interrupt output shares the SDO pin. INT_HL stays at its reset value 0 (active low),
 * whose idle-high level matches the default address 0x77.
 */
public class Spl06Full extends Spl06Minimal {

    /** Interrupt source: a pressure result is ready (INT_STS.INT_PRS). */
    public static final int SOURCE_PRESSURE    = 0x01;
    /** Interrupt source: a temperature result is ready (INT_STS.INT_TMP). */
    public static final int SOURCE_TEMPERATURE = 0x02;
    /** Interrupt source: the FIFO is full (INT_STS.INT_FIFO_FULL). */
    public static final int SOURCE_FIFO_FULL   = 0x04;

    /** FIFO entry kind: pressure (value in Pa). */
    public static final String KIND_PRESSURE    = "pressure";
    /** FIFO entry kind: temperature (value in °C). */
    public static final String KIND_TEMPERATURE = "temperature";

    private static final int CFG_INT_HL   = 0x80;
    private static final int CFG_INT_FIFO = 0x40;
    private static final int CFG_INT_PRS  = 0x20;
    private static final int CFG_INT_TMP  = 0x10;
    private static final int CFG_FIFO_EN  = 0x02;

    /**
     * One FIFO entry.
     *
     * @param kind  {@link #KIND_PRESSURE} or {@link #KIND_TEMPERATURE}
     * @param value pressure in Pa or temperature in °C
     */
    public record FifoEntry(String kind, double value) {}

    private int pRate = 0;
    private int tRate = 0;
    private int continuous = 0;
    private volatile IntConsumer callback;
    private InputPin subscribedPin;
    private volatile boolean polling;
    private Thread pollThread;
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C register connection bound to address 0x77 or 0x76
     */
    public Spl06Full(RegisterConnection connection) {
        super(connection);
    }

    private static int log2(int value, String name) {
        for (int i = 0; i < 8; i++) if (value == 1 << i) return i;
        throw new IllegalArgumentException(name + " must be 1, 2, 4, 8, 16, 32, 64 or 128");
    }

    private void writeCfg() throws IOException {
        writeReg(REG_CFG_REG, cfg);
    }

    /**
     * Measure temperature, then pressure; in background mode return the latest results instead.
     *
     * @return temperature in °C and pressure in Pa
     * @throws IOException on I²C error or a timeout
     */
    @Override
    public Measurement read() throws IOException {
        if (continuous != 0) return readContinuous();
        return super.read();
    }

    /**
     * Measure temperature and pressure and return the compensated pressure.
     *
     * @return pressure in Pa
     * @throws IOException on I²C error or a timeout
     */
    public double readPressure() throws IOException {
        return read().pressurePa();
    }

    /**
     * Measure and return the temperature.
     *
     * @return temperature in °C
     * @throws IOException on I²C error or a timeout
     */
    public double readTemperature() throws IOException {
        if (continuous != 0) return readContinuous().temperatureC();
        int traw = measure(CMD_TEMPERATURE, MEAS_TMP_RDY, tOs);
        lastTraw = traw;
        return temperature(traw);
    }

    /**
     * Raw 24-bit two's-complement results. Command mode measures temperature, then pressure;
     * background mode reads the latest results without starting a measurement.
     *
     * @return {@code {praw, traw}}
     * @throws IOException on I²C error or a timeout
     */
    public int[] readRaw() throws IOException {
        if (continuous != 0) {
            byte[] b = connection.read(REG_PRS_B2, 6);
            int traw = raw24(b, 3);
            lastTraw = traw;
            return new int[]{raw24(b, 0), traw};
        }
        return measureRaw();
    }

    /**
     * Write PRS_CFG and P_SHIFT (set above 8×).
     *
     * @param oversampling 1, 2, 4, 8, 16, 32, 64 or 128 (default 16)
     * @param rate         background measurements per second: 1, 2, 4, …, 128 (default 1)
     * @throws IOException on I²C error
     * @throws IllegalArgumentException for values other than powers of two 1-128
     */
    public void setPressureConfig(int oversampling, int rate) throws IOException {
        int os = log2(oversampling, "oversampling");
        int r = log2(rate, "rate");
        writeReg(REG_PRS_CFG, (r << 4) | os);
        pOs = os;
        pRate = r;
        cfg = oversampling > 8 ? (cfg | CFG_P_SHIFT) : (cfg & ~CFG_P_SHIFT & 0xFF);
        writeCfg();
    }

    /**
     * Write TMP_CFG (always TMP_EXT = 1) and T_SHIFT (set above 8×).
     *
     * @param oversampling 1, 2, 4, 8, 16, 32, 64 or 128 (default 1)
     * @param rate         background measurements per second: 1, 2, 4, …, 128 (default 1)
     * @throws IOException on I²C error
     * @throws IllegalArgumentException for values other than powers of two 1-128
     */
    public void setTemperatureConfig(int oversampling, int rate) throws IOException {
        int os = log2(oversampling, "oversampling");
        int r = log2(rate, "rate");
        writeReg(REG_TMP_CFG, TMP_EXT | (r << 4) | os);
        tOs = os;
        tRate = r;
        cfg = oversampling > 8 ? (cfg | CFG_T_SHIFT) : (cfg & ~CFG_T_SHIFT & 0xFF);
        writeCfg();
    }

    /**
     * Start background mode (MEAS_CTRL = 101 / 110 / 111).
     *
     * @param pressure    measure pressure continuously
     * @param temperature measure temperature continuously
     * @throws IOException on I²C error
     * @throws IllegalArgumentException if both are false
     */
    public void startContinuous(boolean pressure, boolean temperature) throws IOException {
        if (!pressure && !temperature) throw new IllegalArgumentException("select pressure and/or temperature");
        int mode = 0x04 | (pressure ? 0x01 : 0) | (temperature ? 0x02 : 0);
        writeReg(REG_MEAS_CFG, mode);
        continuous = mode;
    }

    /**
     * Start background mode for pressure and temperature.
     *
     * @throws IOException on I²C error
     */
    public void startContinuous() throws IOException {
        startContinuous(true, true);
    }

    /**
     * Stop background mode and return to standby (MEAS_CTRL = 000).
     *
     * @throws IOException on I²C error
     */
    public void stop() throws IOException {
        writeReg(REG_MEAS_CFG, CMD_IDLE);
        continuous = 0;
    }

    /**
     * Wait for the background ready flags and return the latest results (no new measurement).
     *
     * @return temperature in °C and pressure in Pa
     * @throws IOException on I²C error or a timeout
     */
    public Measurement readContinuous() throws IOException {
        int flags = 0;
        if ((continuous & 0x01) != 0) flags |= MEAS_PRS_RDY;
        if ((continuous & 0x02) != 0) flags |= MEAS_TMP_RDY;
        if (flags != 0) {
            long deadline = System.currentTimeMillis() + 2000L / (1 << Math.min(pRate, tRate)) + 500;
            while ((readReg(REG_MEAS_CFG) & flags) != flags) {
                if (System.currentTimeMillis() > deadline) throw new IOException("SPL06 background data not ready");
                sleepMs(2);
            }
        }
        byte[] b = connection.read(REG_PRS_B2, 6);
        int praw = raw24(b, 0);
        int traw = raw24(b, 3);
        lastTraw = traw;
        return new Measurement(temperature(traw), pressure(praw, traw));
    }

    /**
     * Read the MEAS_CFG ready flags.
     *
     * @return {@code {pressureReady, temperatureReady}}
     * @throws IOException on I²C error
     */
    public boolean[] dataReady() throws IOException {
        int m = readReg(REG_MEAS_CFG);
        return new boolean[]{(m & MEAS_PRS_RDY) != 0, (m & MEAS_TMP_RDY) != 0};
    }

    /**
     * Enable the 32-entry result FIFO (CFG_REG.FIFO_EN).
     *
     * @throws IOException on I²C error
     */
    public void enableFifo() throws IOException {
        cfg |= CFG_FIFO_EN;
        writeCfg();
    }

    /**
     * Disable the result FIFO.
     *
     * @throws IOException on I²C error
     */
    public void disableFifo() throws IOException {
        cfg &= ~CFG_FIFO_EN & 0xFF;
        writeCfg();
    }

    /**
     * Drain the FIFO until FIFO_EMPTY (at most 32 entries). The LSB of each entry flags its kind
     * (1 = pressure, 0 = temperature); pressure entries are compensated with the most recent
     * temperature entry (or the last measured temperature).
     *
     * @return entries in measured order
     * @throws IOException on I²C error
     */
    public List<FifoEntry> readFifo() throws IOException {
        List<FifoEntry> out = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            if ((readReg(REG_FIFO_STS) & 0x01) != 0) break;
            int raw = raw24(connection.read(REG_PRS_B2, 3), 0);
            if (raw == -0x800000) break;
            if ((raw & 1) != 0) {
                out.add(new FifoEntry(KIND_PRESSURE, pressure(raw & ~1, lastTraw)));
            } else {
                lastTraw = raw;
                out.add(new FifoEntry(KIND_TEMPERATURE, temperature(raw)));
            }
        }
        return out;
    }

    /**
     * Discard the FIFO contents (RESET.FIFO_FLUSH).
     *
     * @throws IOException on I²C error
     */
    public void flushFifo() throws IOException {
        writeReg(REG_RESET, 0x80);
    }

    /**
     * Read FIFO_STS.
     *
     * @return {@code {empty, full}}
     * @throws IOException on I²C error
     */
    public boolean[] fifoStatus() throws IOException {
        int s = readReg(REG_FIFO_STS);
        return new boolean[]{(s & 0x01) != 0, (s & 0x02) != 0};
    }

    /**
     * Soft reset (RESET = 0x09), wait for the ready flags, re-read the coefficients and restore
     * the {@link #init()} defaults.
     *
     * @throws IOException on I²C error or a chip that does not become ready
     */
    public void reset() throws IOException {
        try {
            writeReg(REG_RESET, 0x09);
        } catch (IOException e) {
            // the chip may not acknowledge while it resets
        }
        sleepMs(12);
        waitReady();
        readCoefficients();
        applyDefaults();
        pRate = 0;
        tRate = 0;
        continuous = 0;
    }

    /**
     * Decoded calibration coefficients.
     *
     * @return c0, c1, c00, c10, c01, c11, c20, c21, c30
     */
    public Coefficients coefficients() {
        return coef;
    }

    /**
     * Compensate raw values with the stored coefficients (no bus access).
     *
     * @param praw          signed raw pressure
     * @param traw          signed raw temperature
     * @param pOversampling pressure oversampling of {@code praw}
     * @param tOversampling temperature oversampling of {@code traw}
     * @return temperature in °C and pressure in Pa
     * @throws IllegalArgumentException for an oversampling other than a power of two 1-128
     */
    public Measurement compensate(int praw, int traw, int pOversampling, int tOversampling) {
        double p = praw / SCALE[log2(pOversampling, "pOversampling")];
        double t = traw / SCALE[log2(tOversampling, "tOversampling")];
        return new Measurement(compT(coef, t), compP(coef, p, t));
    }

    /**
     * Measure the pressure and convert it to altitude (international barometric formula).
     *
     * @param seaLevelPa reference sea-level pressure in Pa (typically 101325)
     * @return altitude in m
     * @throws IOException on I²C error or a timeout
     */
    public double altitude(double seaLevelPa) throws IOException {
        double p = readPressure();
        return 44330.0 * (1.0 - Math.pow(p / seaLevelPa, 1.0 / 5.255));
    }

    /**
     * Altitude against the standard sea-level pressure of 101325 Pa.
     *
     * @return altitude in m
     * @throws IOException on I²C error or a timeout
     */
    public double altitude() throws IOException {
        return altitude(101325.0);
    }

    private static int intBits(int source) {
        int bits = 0;
        if ((source & SOURCE_PRESSURE) != 0) bits |= CFG_INT_PRS;
        if ((source & SOURCE_TEMPERATURE) != 0) bits |= CFG_INT_TMP;
        if ((source & SOURCE_FIFO_FULL) != 0) bits |= CFG_INT_FIFO;
        return bits;
    }

    /**
     * Enable interrupt sources on SDO (CFG_REG.INT_PRS / INT_TMP / INT_FIFO).
     *
     * @param source SOURCE_* constant or a bitwise OR of them
     * @throws IOException on I²C error
     */
    public void enableInterrupt(int source) throws IOException {
        cfg |= intBits(source);
        writeCfg();
    }

    /**
     * Disable interrupt sources.
     *
     * @param source SOURCE_* constant or a bitwise OR of them
     * @throws IOException on I²C error
     */
    public void disableInterrupt(int source) throws IOException {
        cfg &= ~intBits(source) & 0xFF;
        writeCfg();
    }

    /**
     * Read INT_STS (cleared on read).
     *
     * @return bitmask of SOURCE_* flags (0 = none)
     * @throws IOException on I²C error
     */
    public int pollInterrupt() throws IOException {
        return readReg(REG_INT_STS) & 0x07;
    }

    /**
     * Subscribe to the interrupt using {@code connection.intPin()} (edge matching INT_HL), or a
     * 5 ms polling thread when the connection has no INT pin.
     *
     * @param callback receives the SOURCE_* bitmask
     */
    public void onInterrupt(IntConsumer callback) {
        onInterrupt(callback, connection.intPin());
    }

    /**
     * Subscribe to the interrupt on an explicit pin.
     *
     * @param callback receives the SOURCE_* bitmask
     * @param intPin   pin wired to SDO, or {@code null} for the polling fallback
     */
    public void onInterrupt(IntConsumer callback, InputPin intPin) {
        offInterrupt();
        this.callback = callback;
        if (intPin != null) {
            subscribedPin = intPin;
            intPin.onEdge(edgeHandler, (cfg & CFG_INT_HL) != 0 ? EdgeTrigger.RISING : EdgeTrigger.FALLING);
        } else {
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
            }, "spl06-poll");
            pollThread.setDaemon(true);
            pollThread.start();
        }
    }

    /** Unsubscribe and stop delivery. */
    public void offInterrupt() {
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

    private void handleEdge() {
        IntConsumer cb = callback;
        if (cb == null) return;
        try {
            int status = pollInterrupt();
            if (status != 0) cb.accept(status);
        } catch (IOException e) {
            // a failed poll is retried on the next edge / tick
        }
    }
}
