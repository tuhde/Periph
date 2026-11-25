package it.uhde.periph.chips.light

import groovy.transform.CompileStatic
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection

import java.util.function.IntConsumer

/**
 * Si1132 full driver — extends {@link Si1132Minimal} with single channels, auxiliary channels, gain
 * and range, alignment, UV coefficients, calibration, autonomous mode, command response, address
 * change, reset and the INT pin.
 */
@CompileStatic
class Si1132Full extends Si1132Minimal {

    /** {@code IRQ_STATUS} flag: ALS/UV measurement complete. */
    static final int STATUS_ALS_INT = 0x01

    private volatile IntConsumer callback
    private InputPin intPin
    private Thread pollThread
    private volatile boolean polling
    private final EdgeHandler edgeHandler = new EdgeHandler() {
        @Override
        void onEdge() {
            handleEdge()
        }
    }

    /**
     * Construct the driver and run the initialization.
     *
     * @param connection I²C register connection bound to 0x60
     * @throws IOException on bus error
     */
    Si1132Full(RegisterConnection connection) {
        super(connection)
    }

    /**
     * Forced UV measurement.
     *
     * @return UV index ({@code AUX_DATA / 100})
     * @throws IOException on bus error or timeout
     */
    double readUvIndex() {
        return measure()[2] / 100.0d
    }

    /**
     * Forced measurement of the visible channel.
     *
     * @return raw 16-bit visible count
     * @throws IOException on bus error or timeout
     */
    int readVisible() {
        return measure()[0]
    }

    /**
     * Forced measurement of the infrared channel.
     *
     * @return raw 16-bit infrared count
     * @throws IOException on bus error or timeout
     */
    int readInfrared() {
        return measure()[1]
    }

    /**
     * Read the latest data registers without starting a measurement.
     *
     * @return {visible, infrared, aux}
     * @throws IOException on bus error
     */
    int[] readRaw() {
        return readData()
    }

    private int readAux(int mux) {
        paramSet(PARAM_AUX_ADCMUX, mux)
        paramSet(PARAM_CHLIST, CHLIST_AUX)
        try {
            return measure()[2]
        } finally {
            paramSet(PARAM_CHLIST, chlist)
        }
    }

    /**
     * Measure the auxiliary channel as die temperature ({@code AUX_ADCMUX = 0x65}, relative only).
     *
     * @return raw 16-bit value
     * @throws IOException on bus error or timeout
     */
    int readTemperatureRaw() {
        return readAux(0x65)
    }

    /**
     * Measure the auxiliary channel as supply voltage ({@code AUX_ADCMUX = 0x75}).
     *
     * @return raw 16-bit value
     * @throws IOException on bus error or timeout
     */
    int readVddRaw() {
        return readAux(0x75)
    }

    /**
     * Select the measured channels ({@code CHLIST}).
     *
     * @param uv       enable the UV channel (cannot be combined with {@code aux})
     * @param visible  enable the visible channel
     * @param infrared enable the infrared channel
     * @param aux      enable the auxiliary channel
     * @throws IllegalArgumentException if no channel is selected or both {@code uv} and {@code aux} are set
     * @throws IOException on bus error
     */
    void setChannels(boolean uv, boolean visible, boolean infrared, boolean aux) {
        if (!(uv || visible || infrared || aux)) {
            throw new IllegalArgumentException("at least one channel must be enabled")
        }
        if (uv && aux) {
            throw new IllegalArgumentException("uv and aux share the data registers")
        }
        int v = (uv ? 0x80 : 0) | (aux ? 0x40 : 0) | (infrared ? 0x20 : 0) | (visible ? 0x10 : 0)
        paramSet(PARAM_CHLIST, v)
        chlist = v
    }

    /**
     * Set the ADC gain (integration time x 2^n) and the recommended recovery count.
     *
     * @param visible  visible gain 0–7
     * @param infrared infrared gain 0–7
     * @throws IllegalArgumentException if a gain is outside 0–7
     * @throws IOException on bus error
     */
    void setGain(int visible, int infrared) {
        if (visible < 0 || visible > 7 || infrared < 0 || infrared > 7) {
            throw new IllegalArgumentException("gain must be 0-7")
        }
        paramSet(PARAM_VIS_GAIN, visible)
        paramSet(PARAM_VIS_COUNTER, (~visible & 0x07) << 4)
        paramSet(PARAM_IR_GAIN, infrared)
        paramSet(PARAM_IR_COUNTER, (~infrared & 0x07) << 4)
    }

    /**
     * Select the high signal range (sensitivity ÷ 14.5, for direct sunlight).
     *
     * @param visible  high range for the visible channel
     * @param infrared high range for the infrared channel
     * @throws IOException on bus error
     */
    void setHighRange(boolean visible, boolean infrared) {
        paramSet(PARAM_VIS_MISC, visible ? 0x20 : 0x00)
        paramSet(PARAM_IR_MISC, infrared ? 0x20 : 0x00)
    }

    /**
     * Select which 16 bits of the 17-bit ADC value are reported.
     *
     * @param visibleLow  true = least significant 16 bits, false = the 16 MSBs
     * @param infraredLow same for the infrared channel
     * @throws IOException on bus error
     */
    void setAlignment(boolean visibleLow, boolean infraredLow) {
        paramSet(PARAM_ALS_ENCODING, (visibleLow ? 0x10 : 0) | (infraredLow ? 0x20 : 0))
    }

    /**
     * Write the four UV index coefficients {@code UCOEF0..3}.
     *
     * @param ucoef four byte values
     * @throws IllegalArgumentException if {@code ucoef} does not have four elements
     * @throws IOException on bus error
     */
    void setUvCoefficients(int[] ucoef) {
        if (ucoef.length != 4) {
            throw new IllegalArgumentException("four coefficients required")
        }
        byte[] b = new byte[4]
        for (int i = 0; i < 4; i++) b[i] = (byte) ucoef[i]
        connection.write(REG_UCOEF, b)
    }

    /**
     * Read the factory calibration ({@code GET_CAL}, then registers {@code 0x22}–{@code 0x2D}).
     *
     * @return the 12 calibration bytes
     * @throws IOException on bus error
     */
    int[] readCalibration() {
        command(CMD_GET_CAL)
        byte[] d = connection.read(REG_DATA, 12)
        int[] out = new int[12]
        for (int i = 0; i < 12; i++) out[i] = d[i] & 0xFF
        return out
    }

    /**
     * Start autonomous measurements.
     *
     * @param interval wake-up interval in seconds (multiple of 31.25 µs, at most about 2.05 s); 0 returns to forced mode
     * @throws IllegalArgumentException if {@code interval} is negative or too long
     * @throws IOException on bus error
     */
    void startAutonomous(double interval) {
        long counts = Math.round(interval / 31.25e-6d)
        if (interval < 0 || counts > 0xFFFF) {
            throw new IllegalArgumentException("interval out of range")
        }
        if (seqId == 0x01) {   // erratum: MEAS_RATE bytes swapped
            writeReg(REG_MEAS_RATE_SWAPPED, (int) (counts & 0xFF))
            writeReg(REG_MEAS_RATE0, (int) (counts >> 8))
        } else {
            writeReg(REG_MEAS_RATE0, (int) (counts & 0xFF))
            writeReg(REG_MEAS_RATE1, (int) (counts >> 8))
        }
        command(counts != 0 ? CMD_ALS_AUTO : CMD_ALS_PAUSE)
    }

    /**
     * Pause autonomous measurements ({@code ALS_PAUSE}).
     *
     * @throws IOException on bus error
     */
    void pause() {
        command(CMD_ALS_PAUSE)
    }

    /**
     * Start one forced measurement without waiting for it ({@code ALS_FORCE}).
     *
     * @throws IOException on bus error
     */
    void force() {
        startMeasurement()
    }

    /**
     * Check whether a measurement is complete.
     *
     * @return true when {@code IRQ_STATUS.ALS_INT} is set
     * @throws IOException on bus error
     */
    boolean measurementReady() {
        return (readReg(REG_IRQ_STATUS) & IRQ_ALS_INT) != 0
    }

    /**
     * Decode the {@code RESPONSE} register.
     *
     * @return {counter, error}; error 0 = none, otherwise the raw 0x8x code
     * @throws IOException on bus error
     */
    int[] readResponse() {
        int resp = readReg(REG_RESPONSE)
        return ((resp & RESP_ERROR) != 0 ? [0, resp] : [resp & 0x0F, 0]) as int[]
    }

    /**
     * Change the I²C address (volatile; lost at reset).
     *
     * @param address new 7-bit address written to the {@code I2C_ADDR} parameter
     * @throws IOException on bus error
     */
    void setI2cAddress(int address) {
        paramSet(PARAM_I2C_ADDR, address)
        command(CMD_BUSADDR)
    }

    /**
     * Software reset: {@code RESET} command, 2 ms wait, {@code HW_KEY} again.
     *
     * @throws IOException on bus error
     */
    void reset() {
        writeReg(REG_COMMAND, CMD_RESET)
        sleepMs(2)
        writeReg(REG_HW_KEY, HW_KEY_VALUE)
    }

    /**
     * Drive INT low on completed measurements ({@code INT_OE} and {@code ALS_IE}).
     *
     * @throws IOException on bus error
     */
    void enableInterrupt() {
        writeReg(REG_IRQ_ENABLE, IRQ_ALS_INT)
        writeReg(REG_INT_CFG, 0x01)
    }

    /**
     * Release INT ({@code INT_OE = 0}); {@code ALS_IE} stays set because {@link #read()} polls {@code ALS_INT}.
     *
     * @throws IOException on bus error
     */
    void disableInterrupt() {
        writeReg(REG_INT_CFG, 0x00)
    }

    /**
     * Read {@code IRQ_STATUS} and write it back to clear the flags.
     *
     * @return the status value (0 = nothing pending)
     * @throws IOException on bus error
     */
    int pollInterrupt() {
        int status = readReg(REG_IRQ_STATUS)
        if (status != 0) writeReg(REG_IRQ_STATUS, status)
        return status
    }

    /**
     * Subscribe to measurement-complete events. Enables the INT output and uses the connection's
     * INT pin (falling edge); without a pin a polling thread calls {@link #pollInterrupt()} every 10 ms.
     *
     * @param callback called with the {@code IRQ_STATUS} value
     * @throws IOException on bus error
     */
    void onInterrupt(IntConsumer callback) {
        offInterrupt()
        enableInterrupt()
        this.callback = callback
        InputPin pin = connection.intPin()
        if (pin != null) {
            intPin = pin
            pin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            startPolling()
        }
    }

    /**
     * Unsubscribe from measurement events and release the INT output.
     *
     * @throws IOException on bus error
     */
    void offInterrupt() {
        boolean had = callback != null
        callback = null
        if (intPin != null) {
            intPin.offEdge(edgeHandler)
            intPin = null
        }
        stopPolling()
        if (had) disableInterrupt()
    }

    private void startPolling() {
        polling = true
        pollThread = new Thread({
            while (polling) {
                try {
                    handleEdge()
                    Thread.sleep(10)
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        } as Runnable, "si1132-poll")
        pollThread.setDaemon(true)
        pollThread.start()
    }

    private void stopPolling() {
        polling = false
        if (pollThread != null) {
            pollThread.interrupt()
            pollThread = null
        }
    }

    private void handleEdge() {
        try {
            int status = pollInterrupt()
            IntConsumer cb = callback
            if (status != 0 && cb != null) cb.accept(status)
        } catch (IOException ignored) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }
}
