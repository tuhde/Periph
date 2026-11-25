package it.uhde.periph.chips.tof

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection

import groovy.transform.CompileStatic
import java.io.IOException
import java.util.function.IntConsumer

/**
 * VL6180X full interface — extends {@link VL6180XMinimal} with ALS (lux), continuous and
 * interleaved modes, thresholds, gain / integration / scaler, convergence and averaging, range
 * offset and crosstalk, VHV recalibration, address change, identification, the history buffer and
 * the Level-2 interrupt API.
 *
 * <p>{@link #onInterrupt(IntConsumer)} uses {@code connection.intPin()} if wired (falling edge,
 * GPIO1 is active low), otherwise a 5&nbsp;ms polling thread.
 */
@CompileStatic
class VL6180XFull extends VL6180XMinimal {

    private volatile IntConsumer callback
    private InputPin subscribedPin
    private volatile boolean polling = false
    private Thread pollThread
    private final EdgeHandler edgeHandler = { handleEdge() } as EdgeHandler

    /**
     * Create the driver and run the initialization sequence.
     *
     * @param connection I²C connection with a register width of 2, bound to 0x29
     * @throws IOException on bus error
     */
    public VL6180XFull(RegisterConnection connection) throws IOException {
        super(connection)
    }

    /**
     * Take one single-shot range measurement without failing on range errors.
     *
     * @return {range in mm, error code}; the range is valid only when the code is 0
     * @throws IOException on bus error or if no result arrives within 100&nbsp;ms
     */
    public synchronized int[] readRangeStatus() throws IOException {
        return measureRange()
    }

    /**
     * Start continuous ranging.
     *
     * @param periodMs period in ms, 10–2550 in 10&nbsp;ms steps
     * @throws IOException on bus error
     * @throws IllegalArgumentException if out of range
     */
    public synchronized void startContinuousRange(int periodMs) throws IOException {
        if (periodMs < 10 || periodMs > 2550) throw new IllegalArgumentException("period must be 10 to 2550 ms")
        wr8(REG_RANGE_INTERMEASUREMENT, periodMs.intdiv(10) - 1)
        wr8(REG_INTERRUPT_CLEAR, 0x07)
        wr8(REG_RANGE_START, 0x03)
    }

    /**
     * Stop continuous ranging (the start/stop bit toggles the running mode).
     *
     * @throws IOException on bus error
     */
    public synchronized void stopContinuousRange() throws IOException {
        wr8(REG_RANGE_START, 0x01)
    }

    /**
     * Wait for the next continuous-mode range sample and read it.
     *
     * @return the distance in mm
     * @throws IOException on bus error, a non-zero range error code or a timeout
     */
    public synchronized int readRangeContinuous() throws IOException {
        int period = (rd8(REG_RANGE_INTERMEASUREMENT) + 1) * 10
        waitSample(0, RANGE_TIMEOUT_MS + period)
        int[] r = readRangeResult()
        if (r[1] != 0) throw new IOException("VL6180X range error " + r[1])
        return r[0]
    }

    /**
     * Take one single-shot ALS measurement.
     *
     * @return the 16-bit ALS count
     * @throws IOException on bus error or if no result arrives within the integration time + 100&nbsp;ms
     */
    public synchronized int readAlsRaw() throws IOException {
        wr8(REG_ALS_START, 0x01)
        try {
            waitSample(3, integrationMs + 100)
        } catch (IOException e) {
            wr8(REG_INTERRUPT_CLEAR, 0x07)
            throw e
        }
        int raw = rd16(REG_ALS_VAL)
        wr8(REG_INTERRUPT_CLEAR, 0x07)
        return raw
    }

    /**
     * Take one single-shot ALS measurement and convert it to lux:
     * {@code 0.32 * (count / gain) * (100 / integration_ms)} with the actual gain of the current code.
     *
     * @return illuminance in lux (without cover glass)
     * @throws IOException on bus error or timeout
     */
    public synchronized double readLux() throws IOException {
        int raw = readAlsRaw()
        return 0.32 * ((double) raw / GAIN[gainCode]) * (100.0 / integrationMs)
    }

    /**
     * Start continuous ALS measurements.
     *
     * @param periodMs period in ms, 10–2550 in 10&nbsp;ms steps
     * @throws IOException on bus error
     * @throws IllegalArgumentException if out of range
     */
    public synchronized void startContinuousAls(int periodMs) throws IOException {
        if (periodMs < 10 || periodMs > 2550) throw new IllegalArgumentException("period must be 10 to 2550 ms")
        wr8(REG_ALS_INTERMEASUREMENT, periodMs.intdiv(10) - 1)
        wr8(REG_INTERRUPT_CLEAR, 0x07)
        wr8(REG_ALS_START, 0x03)
    }

    /**
     * Stop continuous ALS measurements (the start/stop bit toggles the running mode).
     *
     * @throws IOException on bus error
     */
    public synchronized void stopContinuousAls() throws IOException {
        wr8(REG_ALS_START, 0x01)
    }

    /**
     * Enable or disable interleaved ALS + range mode (started with {@link #startContinuousAls(int)}).
     *
     * @param on true to enable
     * @throws IOException on bus error
     */
    public synchronized void setInterleaved(boolean on) throws IOException {
        wr8(REG_INTERLEAVED_ENABLE, on ? 1 : 0)
    }

    /**
     * Set the ALS analogue gain.
     *
     * @param code gain code 0–7 (0 = 20, 1 = 10, 2 = 5, 3 = 2.5, 4 = 1.67, 5 = 1.25, 6 = 1, 7 = 40)
     * @throws IOException on bus error
     * @throws IllegalArgumentException if out of range
     */
    public synchronized void setAlsGain(int code) throws IOException {
        if (code < 0 || code > 7) throw new IllegalArgumentException("gain code must be 0 to 7")
        wr8(REG_GROUPED_PARAM_HOLD, 1)
        wr8(REG_ALS_GAIN, 0x40 | code)
        wr8(REG_GROUPED_PARAM_HOLD, 0)
        gainCode = code
    }

    /**
     * Set the ALS integration time.
     *
     * @param ms integration time in ms, 1–512
     * @throws IOException on bus error
     * @throws IllegalArgumentException if out of range
     */
    public synchronized void setAlsIntegrationTime(int ms) throws IOException {
        if (ms < 1 || ms > 512) throw new IllegalArgumentException("integration time must be 1 to 512 ms")
        wr8(REG_GROUPED_PARAM_HOLD, 1)
        wr16(REG_ALS_INTEGRATION, ms - 1)
        wr8(REG_GROUPED_PARAM_HOLD, 0)
        integrationMs = ms
    }

    /**
     * Set the ALS result scaler ({@code FIRMWARE__RESULT_SCALER}).
     *
     * @param scaler scaler, 1–16
     * @throws IOException on bus error
     * @throws IllegalArgumentException if out of range
     */
    public synchronized void setAlsScaler(int scaler) throws IOException {
        if (scaler < 1 || scaler > 16) throw new IllegalArgumentException("scaler must be 1 to 16")
        wr8(REG_RESULT_SCALER, scaler)
    }

    /**
     * Set the range interrupt thresholds (grouped-parameter-hold protocol).
     *
     * @param low  low threshold in mm, 0–255
     * @param high high threshold in mm, low &lt;= high &lt;= 255
     * @throws IOException on bus error
     * @throws IllegalArgumentException if out of range
     */
    public synchronized void setRangeThresholds(int low, int high) throws IOException {
        if (low < 0 || high > 255 || low > high) throw new IllegalArgumentException("thresholds must satisfy 0 <= low <= high <= 255")
        wr8(REG_GROUPED_PARAM_HOLD, 1)
        wr8(REG_RANGE_THRESH_HIGH, high)
        wr8(REG_RANGE_THRESH_LOW, low)
        wr8(REG_GROUPED_PARAM_HOLD, 0)
    }

    /**
     * Set the ALS interrupt thresholds (grouped-parameter-hold protocol).
     *
     * @param low  low threshold in counts
     * @param high high threshold in counts, low &lt;= high &lt;= 65535
     * @throws IOException on bus error
     * @throws IllegalArgumentException if out of range
     */
    public synchronized void setAlsThresholds(int low, int high) throws IOException {
        if (low < 0 || high > 65535 || low > high) throw new IllegalArgumentException("thresholds must satisfy 0 <= low <= high <= 65535")
        wr8(REG_GROUPED_PARAM_HOLD, 1)
        wr16(REG_ALS_THRESH_HIGH, high)
        wr16(REG_ALS_THRESH_LOW, low)
        wr8(REG_GROUPED_PARAM_HOLD, 0)
    }

    /**
     * Set the maximum ranging convergence time.
     *
     * @param ms time in ms, 1–63
     * @throws IOException on bus error
     * @throws IllegalArgumentException if out of range
     */
    public synchronized void setMaxConvergenceTime(int ms) throws IOException {
        if (ms < 1 || ms > 63) throw new IllegalArgumentException("convergence time must be 1 to 63 ms")
        wr8(REG_RANGE_MAX_CONVERGENCE, ms)
    }

    /**
     * Set the ranging averaging sample period.
     *
     * @param units 0–255; about 64.5&nbsp;µs per unit (48 is the default)
     * @throws IOException on bus error
     * @throws IllegalArgumentException if out of range
     */
    public synchronized void setAveragingPeriod(int units) throws IOException {
        if (units < 0 || units > 255) throw new IllegalArgumentException("averaging period must be 0 to 255")
        wr8(REG_AVERAGING_PERIOD, units)
    }

    /**
     * Set the part-to-part range offset.
     *
     * @param mm offset in mm, -128 to 127 (two's complement on the chip)
     * @throws IOException on bus error
     * @throws IllegalArgumentException if out of range
     */
    public synchronized void setRangeOffset(int mm) throws IOException {
        if (mm < -128 || mm > 127) throw new IllegalArgumentException("offset must be -128 to 127 mm")
        wr8(REG_RANGE_OFFSET, mm & 0xFF)
    }

    /**
     * Read the part-to-part range offset.
     *
     * @return the offset in mm (signed)
     * @throws IOException on bus error
     */
    public synchronized int getRangeOffset() throws IOException {
        return (int) (byte) rd8(REG_RANGE_OFFSET)
    }

    /**
     * Set the crosstalk compensation rate.
     *
     * @param rate rate in Mcps (stored as 9.7 fixed point)
     * @throws IOException on bus error
     * @throws IllegalArgumentException if out of range
     */
    public synchronized void setCrosstalkRate(double rate) throws IOException {
        long code = (long) (rate * 128 + 0.5)
        if (!(rate >= 0) || code > 0xFFFF) throw new IllegalArgumentException("crosstalk rate out of range")
        wr16(REG_RANGE_CROSSTALK_RATE, (int) code)
    }

    /**
     * Start a VHV recalibration and wait for the start bit to clear.
     *
     * @throws IOException on bus error or if it does not finish within 100&nbsp;ms
     */
    public synchronized void recalibrateVhv() throws IOException {
        wr8(REG_VHV_RECALIBRATE, 0x01)
        for (int i = 0; i <= VHV_TIMEOUT_MS; i++) {
            if ((rd8(REG_VHV_RECALIBRATE) & 0x01) == 0) return
            sleepMs(1)
        }
        throw new IOException("VL6180X timeout waiting for VHV recalibration")
    }

    /**
     * Change the chip's I²C address (volatile). The connection passed at construction still points
     * at the old address; create a new connection and driver to continue.
     *
     * @param address new 7-bit address, 0x08–0x77
     * @throws IOException on bus error
     * @throws IllegalArgumentException if out of range
     */
    public synchronized void setAddress(int address) throws IOException {
        if (address < 0x08 || address > 0x77) throw new IllegalArgumentException("address must be 0x08 to 0x77")
        wr8(REG_I2C_ADDRESS, address & 0x7F)
    }

    /**
     * Read the model ID.
     *
     * @return 0xB4 for the VL6180X
     * @throws IOException on bus error
     */
    public synchronized int modelId() throws IOException {
        return rd8(REG_MODEL_ID)
    }

    /**
     * Read the model revision.
     *
     * @return {major, minor}, bits 2:0 of each revision register
     * @throws IOException on bus error
     */
    public synchronized int[] revision() throws IOException {
        return [rd8(REG_MODEL_REV_MAJOR) & 0x07, rd8(REG_MODEL_REV_MINOR) & 0x07] as int[]
    }

    /**
     * Read the eight-entry history buffer (enable it through the history control register first).
     *
     * @return eight 16-bit entries (range in mm or ALS counts, per the history mode)
     * @throws IOException on bus error
     */
    public synchronized int[] readHistory() throws IOException {
        byte[] b = connection.read(REG_HISTORY_BUFFER, 16)
        int[] out = new int[8]
        for (int i = 0; i < 8; i++) out[i] = ((b[2 * i] & 0xFF) << 8) | (b[2 * i + 1] & 0xFF)
        return out
    }

    private void setInterruptMode(int source, int mode) throws IOException {
        int shift
        if (source == SOURCE_RANGE) shift = 0
        else if (source == SOURCE_ALS) shift = 3
        else throw new IllegalArgumentException("source must be SOURCE_RANGE or SOURCE_ALS")
        synchronized (this) {
            int value = (rd8(REG_INTERRUPT_CONFIG) & ~(0x07 << shift) & 0xFF) | (mode << shift)
            wr8(REG_GROUPED_PARAM_HOLD, 1)
            wr8(REG_INTERRUPT_CONFIG, value)
            wr8(REG_GROUPED_PARAM_HOLD, 0)
        }
    }

    /**
     * Select the interrupt mode of the range or ALS source.
     *
     * @param source {@link #SOURCE_RANGE} or {@link #SOURCE_ALS}
     * @param mode   {@link #MODE_LEVEL_LOW}, {@link #MODE_LEVEL_HIGH}, {@link #MODE_OUT_OF_WINDOW}
     *               or {@link #MODE_NEW_SAMPLE_READY}
     * @throws IOException on bus error
     * @throws IllegalArgumentException if source or mode is invalid
     */
    public void enableInterrupt(int source, int mode) throws IOException {
        if (mode < 1 || mode > 4) throw new IllegalArgumentException("mode must be 1 to 4")
        setInterruptMode(source, mode)
    }

    /**
     * Disable the interrupt of the range or ALS source (mode 0).
     *
     * @param source {@link #SOURCE_RANGE} or {@link #SOURCE_ALS}
     * @throws IOException on bus error
     * @throws IllegalArgumentException if source is invalid
     */
    public void disableInterrupt(int source) throws IOException {
        setInterruptMode(source, 0)
    }

    /**
     * Read the interrupt status and clear all pending sources.
     *
     * @return bit mask of {@link #SOURCE_RANGE}, {@link #SOURCE_ALS}, {@link #SOURCE_ERROR} (0 if none)
     * @throws IOException on bus error
     */
    public int pollInterrupt() throws IOException {
        return pollInterruptStatus()
    }

    /**
     * Subscribe to GPIO1 events using {@code connection.intPin()} if wired (falling edge), otherwise
     * a 5&nbsp;ms polling thread. Pending sources are cleared before the callback runs.
     *
     * @param callback called with the {@code SOURCE_*} bit mask
     * @throws IOException on bus error
     */
    public void onInterrupt(IntConsumer callback) throws IOException {
        onInterrupt(callback, connection.intPin())
    }

    /**
     * Subscribe to GPIO1 events, overriding which {@link InputPin} delivers edges.
     *
     * @param callback called with the {@code SOURCE_*} bit mask
     * @param intPin   GPIO1 pin to arm, or {@code null} to force the 5&nbsp;ms polling fallback
     * @throws IOException on bus error
     */
    public void onInterrupt(IntConsumer callback, InputPin intPin) throws IOException {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            subscribedPin = intPin
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = new Thread({
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(5)
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            } as Runnable, "vl6180x-poll")
            pollThread.setDaemon(true)
            pollThread.start()
        }
    }

    /** Detach the edge handler or stop the polling thread, and clear the callback. */
    public void offInterrupt() {
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
        try {
            int status = pollInterruptStatus()
            IntConsumer cb = callback
            if (status != 0 && cb != null) cb.accept(status)
        } catch (IOException ignored) {
            // bus error; wait for the next edge rather than propagating
        }
    }
}
