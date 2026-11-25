package it.uhde.periph.chips.humidity

import groovy.transform.CompileStatic
import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection

import java.util.function.IntConsumer

/**
 * HTS221 full driver — extends {@link Hts221Minimal} with single values, raw outputs, output data
 * rate, averaging, power modes, heater, DRDY pin, calibration access, dew point and data-ready
 * interrupt support.
 */
@CompileStatic
class Hts221Full extends Hts221Minimal {

    /** {@code STATUS_REG} flag: humidity data available. */
    static final int STATUS_H_DA = 0x02
    /** {@code STATUS_REG} flag: temperature data available. */
    static final int STATUS_T_DA = 0x01
    /** Expected {@code WHO_AM_I} value. */
    static final int WHO_AM_I_VALUE = 0xBC

    private static final double[] ODR_HZ = [0.0d, 1.0d, 7.0d, 12.5d] as double[]

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
     * Construct the driver and run the initialization (calibration read, one-shot mode).
     *
     * @param connection I²C register connection bound to 0x5F
     * @throws IOException on bus error
     */
    Hts221Full(RegisterConnection connection) {
        super(connection)
    }

    /**
     * Measure the relative humidity only (one-shot).
     *
     * @return relative humidity in %rH, clamped to 0–100
     */
    double readHumidity() {
        return humidityFromRaw(oneShotRaw()[0])
    }

    /**
     * Measure the temperature only (one-shot).
     *
     * @return temperature in °C
     */
    double readTemperature() {
        return temperatureFromRaw(oneShotRaw()[1])
    }

    /**
     * Measure and return the raw humidity output (one-shot).
     *
     * @return signed 16-bit raw value
     */
    int readRawHumidity() {
        return oneShotRaw()[0]
    }

    /**
     * Measure and return the raw temperature output (one-shot).
     *
     * @return signed 16-bit raw value
     */
    int readRawTemperature() {
        return oneShotRaw()[1]
    }

    /**
     * Read the latest sample without starting a conversion (continuous output data rate).
     *
     * @return {temperature in °C, relative humidity in %rH}
     */
    double[] readContinuous() {
        int[] raw = readRawPair()
        return [temperatureFromRaw(raw[1]), humidityFromRaw(raw[0])] as double[]
    }

    /**
     * Measure once and compute the dew point (Magnus formula, over water).
     *
     * @return dew point in °C
     */
    double dewPoint() {
        double[] m = read()
        double t = m[0]
        double h = Math.max(m[1], 0.01d)
        double g = Math.log(h / 100.0d) + 17.62d * t / (243.12d + t)
        return 243.12d * g / (17.62d - g)
    }

    /**
     * Set the output data rate.
     *
     * @param hz 0 (one-shot), 1, 7 or 12.5
     * @throws IllegalArgumentException for any other value
     */
    void setOutputDataRate(double hz) {
        int code = -1
        for (int i = 0; i < ODR_HZ.length; i++) {
            if (ODR_HZ[i] == hz) code = i
        }
        if (code < 0) {
            throw new IllegalArgumentException("HTS221: output data rate must be 0, 1, 7 or 12.5 Hz")
        }
        int ctrl1 = readReg(REG_CTRL_REG1)
        writeReg(REG_CTRL_REG1, (ctrl1 & ~CTRL1_ODR_MASK) | code)
        // Clear flags left over from continuous sampling so the next one-shot wait does not return early.
        readRawPair()
    }

    /**
     * Read the output data rate.
     *
     * @return 0, 1, 7 or 12.5 Hz
     */
    double getOutputDataRate() {
        return ODR_HZ[readReg(REG_CTRL_REG1) & CTRL1_ODR_MASK]
    }

    private static int log2Index(int value, int min, int max) {
        if (value < min || value > max || (value & (value - 1)) != 0) return -1
        return Integer.numberOfTrailingZeros(value) - Integer.numberOfTrailingZeros(min)
    }

    /**
     * Set the internal averaging ({@code AV_CONF}).
     *
     * @param temperatureSamples 2, 4, 8, 16, 32, 64, 128 or 256
     * @param humiditySamples    4, 8, 16, 32, 64, 128, 256 or 512
     * @throws IllegalArgumentException for any other value
     */
    void setAveraging(int temperatureSamples, int humiditySamples) {
        int t = log2Index(temperatureSamples, 2, 256)
        int h = log2Index(humiditySamples, 4, 512)
        if (t < 0) throw new IllegalArgumentException("HTS221: temperatureSamples must be a power of two, 2..256")
        if (h < 0) throw new IllegalArgumentException("HTS221: humiditySamples must be a power of two, 4..512")
        writeReg(REG_AV_CONF, (t << 3) | h)
    }

    /**
     * Read the internal averaging.
     *
     * @return {temperature samples, humidity samples}
     */
    int[] getAveraging() {
        int v = readReg(REG_AV_CONF)
        return [2 << ((v >> 3) & 0x07), 4 << (v & 0x07)] as int[]
    }

    /**
     * Enable or disable block data update ({@code CTRL_REG1.BDU}).
     *
     * @param enabled true to keep the output registers stable between reading LSB and MSB
     */
    void setBlockDataUpdate(boolean enabled) {
        int ctrl1 = readReg(REG_CTRL_REG1)
        writeReg(REG_CTRL_REG1, enabled ? (ctrl1 | CTRL1_BDU) : (ctrl1 & ~CTRL1_BDU))
    }

    /** Enter power-down mode ({@code CTRL_REG1.PD = 0}). */
    void powerDown() {
        writeReg(REG_CTRL_REG1, readReg(REG_CTRL_REG1) & ~CTRL1_PD)
    }

    /** Leave power-down mode ({@code CTRL_REG1.PD = 1}). */
    void powerUp() {
        writeReg(REG_CTRL_REG1, readReg(REG_CTRL_REG1) | CTRL1_PD)
    }

    /**
     * Switch the internal heater on or off. Do not measure while it is on.
     *
     * @param on true to heat
     */
    void setHeater(boolean on) {
        int ctrl2 = readReg(REG_CTRL_REG2)
        writeReg(REG_CTRL_REG2, on ? (ctrl2 | CTRL2_HEATER) : (ctrl2 & ~CTRL2_HEATER))
    }

    /**
     * Read the heater state.
     *
     * @return true when the heater is on
     */
    boolean isHeaterOn() {
        return (readReg(REG_CTRL_REG2) & CTRL2_HEATER) != 0
    }

    /**
     * Reload the trimming registers ({@code CTRL_REG2.BOOT}), wait for it to clear (timeout 100 ms)
     * and re-read the calibration.
     *
     * @throws IOException on bus error or when BOOT does not clear in time
     */
    void reboot() {
        int ctrl2 = readReg(REG_CTRL_REG2)
        writeReg(REG_CTRL_REG2, ctrl2 | CTRL2_BOOT)
        for (int i = 0; i < TIMEOUT_MS; i++) {
            if ((readReg(REG_CTRL_REG2) & CTRL2_BOOT) == 0) {
                loadCalibration()
                return
            }
            sleepMs(1)
        }
        throw new IOException("HTS221 reboot timeout")
    }

    /**
     * Read the {@code WHO_AM_I} register.
     *
     * @return the device ID (0xBC for the HTS221)
     */
    int whoAmI() {
        return readReg(REG_WHO_AM_I)
    }

    /**
     * Read {@code STATUS_REG}.
     *
     * @return raw status ({@link #STATUS_H_DA} / {@link #STATUS_T_DA})
     */
    int status() {
        return readReg(REG_STATUS)
    }

    /**
     * Check whether new humidity and temperature data are both available.
     *
     * @return true when H_DA and T_DA are both set
     */
    boolean dataReady() {
        return (status() & STATUS_BOTH) == STATUS_BOTH
    }

    /**
     * Decoded calibration coefficients read at construction (and after {@link #reboot()}).
     *
     * @return {H0_rH, H1_rH, T0_degC, T1_degC, H0_T0_OUT, H1_T0_OUT, T0_OUT, T1_OUT}
     */
    double[] readCalibration() {
        return [h0rH, h1rH, t0degC, t1degC, (double) h0t0Out, (double) h1t0Out, (double) t0Out, (double) t1Out] as double[]
    }

    /**
     * Configure the DRDY output pin ({@code CTRL_REG3}).
     *
     * @param enabled    route data-ready to the DRDY pin
     * @param activeHigh false selects active-low polarity
     * @param openDrain  true selects an open-drain output (default push-pull)
     */
    void configureDrdy(boolean enabled, boolean activeHigh, boolean openDrain) {
        int v = 0
        if (!activeHigh) v |= 0x80
        if (openDrain) v |= 0x40
        if (enabled) v |= 0x04
        writeReg(REG_CTRL_REG3, v)
    }

    /**
     * Subscribe to data-ready events.
     *
     * <p>Enables DRDY (active high, push-pull). Delivery uses the connection's {@code intPin()}
     * (rising edge) when one is wired and falls back to a polling thread at the output data rate
     * otherwise. The callback receives {@code STATUS_REG}.
     *
     * @param callback called with the status
     */
    void onInterrupt(IntConsumer callback) {
        offInterrupt()
        configureDrdy(true, true, false)
        this.callback = callback
        InputPin pin = connection.intPin()
        if (pin != null) {
            intPin = pin
            pin.onEdge(edgeHandler, EdgeTrigger.RISING)
        } else {
            startPolling()
        }
    }

    /** Unsubscribe from data-ready events and disable the DRDY output. */
    void offInterrupt() {
        boolean had = callback != null
        callback = null
        if (intPin != null) {
            intPin.offEdge(edgeHandler)
            intPin = null
        }
        stopPolling()
        if (had) configureDrdy(false, true, false)
    }

    /**
     * Read {@code STATUS_REG}; the flags clear when the output registers are read.
     *
     * @return raw status ({@link #STATUS_H_DA} / {@link #STATUS_T_DA})
     */
    int pollInterrupt() {
        return status()
    }

    private void startPolling() {
        polling = true
        pollThread = new Thread({
            while (polling) {
                try {
                    if (dataReady()) handleEdge()
                    double hz = getOutputDataRate()
                    Thread.sleep(hz > 0 ? (long) (1000 / hz) : 100L)
                } catch (IOException e) {
                    // bus error; try again on the next tick
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        } as Runnable, "hts221-poll")
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
            if (cb != null) cb.accept(status)
        } catch (IOException ignored) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }
}
