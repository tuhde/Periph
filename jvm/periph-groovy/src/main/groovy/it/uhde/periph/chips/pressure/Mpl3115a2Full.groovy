package it.uhde.periph.chips.pressure

import groovy.transform.CompileStatic
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection

import java.io.IOException
import java.util.function.IntConsumer

/**
 * MPL3115A2 — full driver: altimeter mode, oversampling, continuous acquisition, offsets,
 * sea-level pressure, targets and windows, min/max capture, the 32-sample FIFO and interrupts.
 * Extends {@link Mpl3115a2Minimal}.
 */
@CompileStatic
class Mpl3115a2Full extends Mpl3115a2Minimal {

    /** Interrupt source: new pressure/altitude or temperature data. */
    static final int SOURCE_DRDY = 0x80
    /** Interrupt source: FIFO overflow or watermark. */
    static final int SOURCE_FIFO = 0x40
    /** Interrupt source: pressure/altitude window. */
    static final int SOURCE_PW   = 0x20
    /** Interrupt source: temperature window. */
    static final int SOURCE_TW   = 0x10
    /** Interrupt source: pressure/altitude threshold. */
    static final int SOURCE_PTH  = 0x08
    /** Interrupt source: temperature threshold. */
    static final int SOURCE_TTH  = 0x04
    /** Interrupt source: pressure/altitude change. */
    static final int SOURCE_PCHG = 0x02
    /** Interrupt source: temperature change. */
    static final int SOURCE_TCHG = 0x01

    /** FIFO disabled. */
    static final int FIFO_OFF = 0
    /** FIFO circular buffer. */
    static final int FIFO_CIRCULAR = 1
    /** FIFO stops when full. */
    static final int FIFO_STOP = 2

    private int fifoMode = 0
    private volatile IntConsumer callback
    private volatile int intPinNo = 1
    private InputPin subscribedPin
    private volatile boolean polling
    private Thread pollThread
    private final EdgeHandler edgeHandler = { handleEdge() } as EdgeHandler

    /**
     * Create the driver; does no bus access.
     *
     * @param connection I²C register connection bound to address 0x60
     */
    Mpl3115a2Full(RegisterConnection connection) {
        super(connection)
    }

    private boolean standby() {
        int c1 = readReg(REG_CTRL_REG1)
        boolean was = (c1 & CTRL1_SBYB) != 0
        if (was) writeReg(REG_CTRL_REG1, c1 & ~CTRL1_SBYB)
        return was
    }

    private void restore(boolean was) {
        if (was) writeReg(REG_CTRL_REG1, ctrl1() | CTRL1_SBYB)
    }

    /**
     * Select barometer or altimeter mode; the chip is dropped to STANDBY and restored.
     *
     * @param altimeter true for altimeter (metres), false for barometer (pascals)
     */
    void setMode(boolean altimeter) {
        boolean was = standby()
        this.alt = altimeter
        writeReg(REG_CTRL_REG1, ctrl1())
        restore(was)
    }

    /** Run one barometer conversion (switching back from altimeter mode if needed). */
    @Override
    double[] read() {
        if (alt) setMode(false)
        return super.read()
    }

    /**
     * Switch to altimeter mode if needed and run one conversion.
     *
     * @return altitude in metres relative to the sea-level pressure (BAR_IN)
     */
    double readAltitude() {
        if (!alt) setMode(true)
        return altitudeFrom(oneShot(), 0)
    }

    /**
     * Set the oversample ratio.
     *
     * @param ratio 1, 2, 4, 8, 16, 32, 64 or 128
     * @throws IllegalArgumentException for any other value
     */
    void setOversample(int ratio) {
        if (ratio < 1 || ratio > 128 || Integer.bitCount(ratio) != 1) {
            throw new IllegalArgumentException('ratio must be 1, 2, 4, ..., 128')
        }
        boolean was = standby()
        this.os = Integer.numberOfTrailingZeros(ratio)
        writeReg(REG_CTRL_REG1, ctrl1())
        restore(was)
    }

    /** @return the current oversample ratio (1..128) */
    int getOversample() {
        return 1 << os
    }

    /**
     * Start periodic acquisition (ACTIVE mode).
     *
     * @param stepExp auto-acquisition step exponent 0-15 (2^n seconds)
     * @throws IllegalArgumentException if stepExp is outside 0-15
     */
    void startContinuous(int stepExp) {
        if (stepExp < 0 || stepExp > 15) throw new IllegalArgumentException('stepExp must be 0-15')
        standby()
        writeReg(REG_CTRL_REG2, (readReg(REG_CTRL_REG2) & 0xF0) | stepExp)
        writeReg(REG_CTRL_REG1, ctrl1() | CTRL1_SBYB)
    }

    /** Stop periodic acquisition (back to STANDBY). */
    void stopContinuous() {
        writeReg(REG_CTRL_REG1, ctrl1())
    }

    /** @return true if new pressure/altitude or temperature data is ready (PTDR) */
    boolean dataReady() {
        return (readReg(REG_DR_STATUS) & DR_PTDR) != 0
    }

    /**
     * Wait for PTDR and read the latest sample.
     *
     * @return {@code [value (Pa or m per mode), temperature in °C]}
     */
    double[] readContinuous() {
        waitPtdr()
        int[] raw = readRegs(REG_OUT_P_MSB, 5)
        double v = alt ? altitudeFrom(raw, 0) : pressureFrom(raw, 0)
        return [v, temperatureFrom(raw, 3)] as double[]
    }

    /**
     * Read the change since the previous sample.
     *
     * @return {@code [delta value (Pa or m), delta temperature in °C]}
     */
    double[] readDelta() {
        int[] raw = readRegs(REG_OUT_P_DELTA, 5)
        double dv
        if (alt) {
            dv = altitudeFrom(raw, 0)
        } else {
            dv = pressureFrom(raw, 0)
            if (dv >= 262144.0d) dv -= 524288.0d
        }
        return [dv, temperatureFrom(raw, 3)] as double[]
    }

    /**
     * Write BAR_IN, the altitude reference.
     *
     * @param pa sea-level pressure in pascals (2 Pa resolution, default 101326)
     */
    void setSeaLevelPressure(double pa) {
        int raw = ((int) Math.round(pa / 2.0d)) & 0xFFFF
        writeRegs(REG_BAR_IN_MSB, raw >> 8, raw & 0xFF)
    }

    /**
     * Write the offset trims.
     *
     * @param pressurePa   -512..+508 Pa (4 Pa steps)
     * @param temperatureC -8..+7.9375 °C (0.0625 steps)
     * @param altitudeM    -128..+127 m
     * @throws IllegalArgumentException if a value is outside its register range
     */
    void setOffsets(double pressurePa, double temperatureC, int altitudeM) {
        long op = Math.round(pressurePa / 4.0d)
        long ot = Math.round(temperatureC / 0.0625d)
        if (op < -128L || op > 127L || ot < -128L || ot > 127L || altitudeM < -128 || altitudeM > 127) {
            throw new IllegalArgumentException('offset out of range')
        }
        writeReg(REG_OFF_P, ((int) op) & 0xFF)
        writeReg(REG_OFF_T, ((int) ot) & 0xFF)
        writeReg(REG_OFF_H, altitudeM & 0xFF)
    }

    /**
     * Program the threshold/window targets.
     *
     * @param value             pressure target in pascals or altitude in metres (per mode)
     * @param window            pressure window in pascals or altitude window in metres
     * @param temperatureC      temperature target (signed integer °C)
     * @param temperatureWindow temperature window (unsigned integer °C)
     */
    void setTarget(double value, double window, int temperatureC, int temperatureWindow) {
        int tgt = ((int) (alt ? Math.round(value) : Math.round(value / 2.0d))) & 0xFFFF
        int wnd = ((int) (alt ? Math.round(window) : Math.round(window / 2.0d))) & 0xFFFF
        boolean was = standby()
        writeRegs(REG_P_TGT_MSB, tgt >> 8, tgt & 0xFF)
        writeReg(REG_T_TGT, temperatureC & 0xFF)
        writeRegs(REG_P_WND_MSB, wnd >> 8, wnd & 0xFF)
        writeReg(REG_T_WND, temperatureWindow & 0xFF)
        restore(was)
    }

    /** Use the current output as the threshold target (ALARM_SEL = 1, pulse LOAD_OUTPUT). */
    void loadCurrentAsTarget() {
        int c2 = readReg(REG_CTRL_REG2)
        writeReg(REG_CTRL_REG2, (c2 | 0x10) & ~0x20)
        writeReg(REG_CTRL_REG2, c2 | 0x30)
    }

    /**
     * Read the captured extremes.
     *
     * @return {@code [pMin, pMax, tMin, tMax]} — pascals (metres in altimeter mode) and °C
     */
    double[] readMinMax() {
        int[] pm = readRegs(REG_P_MIN, 3)
        int[] px = readRegs(REG_P_MAX, 3)
        int[] tm = readRegs(REG_T_MIN, 2)
        int[] tx = readRegs(REG_T_MAX, 2)
        return [alt ? altitudeFrom(pm, 0) : pressureFrom(pm, 0),
                alt ? altitudeFrom(px, 0) : pressureFrom(px, 0),
                temperatureFrom(tm, 0), temperatureFrom(tx, 0)] as double[]
    }

    /** Clear the min/max registers. */
    void clearMinMax() {
        writeRegs(REG_P_MIN, 0, 0, 0)
        writeRegs(REG_T_MIN, 0, 0)
        writeRegs(REG_P_MAX, 0, 0, 0)
        writeRegs(REG_T_MAX, 0, 0)
    }

    /**
     * Configure the FIFO.
     *
     * @param mode      0 off, 1 circular, 2 stop when full
     * @param watermark watermark sample count 0-63 (0 disables the flag)
     * @throws IllegalArgumentException for an invalid mode or a direct switch between modes 1 and 2
     */
    void fifoSetup(int mode, int watermark) {
        if (mode < 0 || mode > 2 || (fifoMode != 0 && mode != 0 && mode != fifoMode)) {
            throw new IllegalArgumentException('invalid FIFO mode or direct switch between modes')
        }
        boolean was = standby()
        writeReg(REG_F_SETUP, (mode << 6) | (watermark & 0x3F))
        this.fifoMode = mode
        restore(was)
    }

    /**
     * Read F_STATUS.
     *
     * @return {@code [overflow (0/1), watermark (0/1), count]}
     */
    int[] fifoStatus() {
        int s = readReg(REG_F_STATUS)
        return [(s >> 7) & 1, (s >> 6) & 1, s & 0x3F] as int[]
    }

    /**
     * Drain the FIFO.
     *
     * @return one {@code [value (Pa or m), temperature in °C]} per sample
     */
    List<double[]> fifoRead() {
        int count = fifoStatus()[2]
        List<double[]> out = new ArrayList<double[]>()
        for (int i = 0; i < count; i++) {
            int[] raw = readRegs(REG_F_DATA, 5)
            double v = alt ? altitudeFrom(raw, 0) : pressureFrom(raw, 0)
            out.add([v, temperatureFrom(raw, 3)] as double[])
        }
        return out
    }

    /** Software reset, then restore the default configuration (as {@link #init()}). */
    void reset() {
        softReset()
        this.os = 7
        this.alt = false
        this.fifoMode = 0
        applyConfig()
    }

    /** @return the WHO_AM_I register (0xC4) */
    int whoAmI() {
        return readReg(REG_WHO_AM_I)
    }

    /** @return true if SYSMOD reports ACTIVE mode */
    boolean isActive() {
        return (readReg(REG_SYSMOD) & 0x01) != 0
    }

    /**
     * Write CTRL_REG3: interrupt polarity (true = active high) and open-drain per pin.
     */
    void setInterruptPins(boolean polarity1, boolean openDrain1, boolean polarity2, boolean openDrain2) {
        boolean was = standby()
        writeReg(REG_CTRL_REG3, (polarity1 ? 0x20 : 0) | (openDrain1 ? 0x10 : 0)
            | (polarity2 ? 0x02 : 0) | (openDrain2 ? 0x01 : 0))
        restore(was)
    }

    /**
     * Enable an interrupt source and route it to INT1 or INT2.
     *
     * @param source a SOURCE_* constant (or an OR of them)
     * @param pin    1 or 2
     * @throws IllegalArgumentException if pin is not 1 or 2
     */
    void enableInterrupt(int source, int pin) {
        if (pin != 1 && pin != 2) throw new IllegalArgumentException('pin must be 1 or 2')
        boolean was = standby()
        int c4 = readReg(REG_CTRL_REG4)
        int c5 = readReg(REG_CTRL_REG5)
        writeReg(REG_CTRL_REG4, c4 | source)
        writeReg(REG_CTRL_REG5, pin == 1 ? (c5 | source) : (c5 & ~source))
        restore(was)
    }

    /** Disable an interrupt source (clears it in CTRL_REG4). */
    void disableInterrupt(int source) {
        boolean was = standby()
        writeReg(REG_CTRL_REG4, readReg(REG_CTRL_REG4) & ~source)
        restore(was)
    }

    /** @return INT_SOURCE, a bitmask of SOURCE_* flags */
    int pollInterrupt() {
        return readReg(REG_INT_SOURCE)
    }

    /**
     * Subscribe to INT1 or INT2 using {@code connection.intPin()} (falling edge), or a 100 ms
     * polling thread if none is wired. The callback receives INT_SOURCE masked to the sources
     * routed to that pin.
     *
     * @param callback receives the status bitmask
     * @param pin      1 or 2
     */
    void onInterrupt(IntConsumer callback, int pin) {
        onInterrupt(callback, pin, connection.intPin())
    }

    /**
     * Subscribe to INT1 or INT2 on an explicit pin.
     *
     * @param callback receives the status bitmask
     * @param pin      1 or 2
     * @param intPin   pin to arm, or null to force the polling fallback
     */
    void onInterrupt(IntConsumer callback, int pin, InputPin intPin) {
        if (pin != 1 && pin != 2) throw new IllegalArgumentException('pin must be 1 or 2')
        offInterrupt()
        this.callback = callback
        this.intPinNo = pin
        if (intPin != null) {
            subscribedPin = intPin
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = new Thread({
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(100)
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            } as Runnable, 'mpl3115a2-poll')
            pollThread.daemon = true
            pollThread.start()
        }
    }

    /** Unsubscribe and stop delivery. */
    void offInterrupt() {
        callback = null
        if (subscribedPin != null) {
            subscribedPin.offEdge(edgeHandler)
            subscribedPin = null
        }
        polling = false
        if (pollThread != null) {
            pollThread.interrupt()
            pollThread = null
        }
    }

    private void handleEdge() {
        IntConsumer cb = callback
        if (cb == null) return
        try {
            int status = pollInterrupt()
            int c5 = readReg(REG_CTRL_REG5)
            status &= (intPinNo == 1) ? c5 : (~c5 & 0xFF)
            if (status != 0) cb.accept(status)
        } catch (IOException ignored) {
            // transient bus error: the next edge or tick retries
        }
    }
}
