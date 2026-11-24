package it.uhde.periph.chips.humidity;

import it.uhde.periph.connection.EdgeHandler;
import it.uhde.periph.connection.EdgeTrigger;
import it.uhde.periph.connection.InputPin;
import it.uhde.periph.connection.RegisterConnection;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * HTS221 full driver — extends {@link Hts221Minimal} with single values, raw outputs, output data
 * rate, averaging, power modes, heater, DRDY pin, calibration access, dew point and data-ready
 * interrupt support.
 */
public class Hts221Full extends Hts221Minimal {

    /** {@code STATUS_REG} flag: humidity data available. */
    public static final int STATUS_H_DA = 0x02;
    /** {@code STATUS_REG} flag: temperature data available. */
    public static final int STATUS_T_DA = 0x01;
    /** Expected {@code WHO_AM_I} value. */
    public static final int WHO_AM_I_VALUE = 0xBC;

    private static final double[] ODR_HZ = {0.0, 1.0, 7.0, 12.5};

    private volatile IntConsumer callback;
    private InputPin intPin;
    private Thread pollThread;
    private volatile boolean polling;
    private final EdgeHandler edgeHandler = this::handleEdge;

    /**
     * Construct the driver and run the initialization (calibration read, one-shot mode).
     *
     * @param connection I²C register connection bound to 0x5F
     * @throws IOException on bus error
     */
    public Hts221Full(RegisterConnection connection) throws IOException {
        super(connection);
    }

    /**
     * Measure the relative humidity only (one-shot).
     *
     * @return relative humidity in %rH, clamped to 0–100
     * @throws IOException on bus error or timeout
     */
    public double readHumidity() throws IOException {
        return humidityFromRaw(oneShotRaw()[0]);
    }

    /**
     * Measure the temperature only (one-shot).
     *
     * @return temperature in °C
     * @throws IOException on bus error or timeout
     */
    public double readTemperature() throws IOException {
        return temperatureFromRaw(oneShotRaw()[1]);
    }

    /**
     * Measure and return the raw humidity output (one-shot).
     *
     * @return signed 16-bit raw value
     * @throws IOException on bus error or timeout
     */
    public int readRawHumidity() throws IOException {
        return oneShotRaw()[0];
    }

    /**
     * Measure and return the raw temperature output (one-shot).
     *
     * @return signed 16-bit raw value
     * @throws IOException on bus error or timeout
     */
    public int readRawTemperature() throws IOException {
        return oneShotRaw()[1];
    }

    /**
     * Read the latest sample without starting a conversion (continuous output data rate).
     *
     * @return {temperature in °C, relative humidity in %rH}
     * @throws IOException on bus error
     */
    public double[] readContinuous() throws IOException {
        int[] raw = readRawPair();
        return new double[]{temperatureFromRaw(raw[1]), humidityFromRaw(raw[0])};
    }

    /**
     * Measure once and compute the dew point (Magnus formula, over water).
     *
     * @return dew point in °C
     * @throws IOException on bus error or timeout
     */
    public double dewPoint() throws IOException {
        double[] m = read();
        double t = m[0];
        double h = Math.max(m[1], 0.01);
        double g = Math.log(h / 100.0) + 17.62 * t / (243.12 + t);
        return 243.12 * g / (17.62 - g);
    }

    /**
     * Set the output data rate.
     *
     * @param hz 0 (one-shot), 1, 7 or 12.5
     * @throws IOException on bus error
     * @throws IllegalArgumentException for any other value
     */
    public void setOutputDataRate(double hz) throws IOException {
        int code = -1;
        for (int i = 0; i < ODR_HZ.length; i++) {
            if (ODR_HZ[i] == hz) code = i;
        }
        if (code < 0) {
            throw new IllegalArgumentException("HTS221: output data rate must be 0, 1, 7 or 12.5 Hz");
        }
        int ctrl1 = readReg(REG_CTRL_REG1);
        writeReg(REG_CTRL_REG1, (ctrl1 & ~CTRL1_ODR_MASK) | code);
        // Clear flags left over from continuous sampling so the next one-shot wait does not return early.
        readRawPair();
    }

    /**
     * Read the output data rate.
     *
     * @return 0, 1, 7 or 12.5 Hz
     * @throws IOException on bus error
     */
    public double getOutputDataRate() throws IOException {
        return ODR_HZ[readReg(REG_CTRL_REG1) & CTRL1_ODR_MASK];
    }

    private static int log2Index(int value, int min, int max) {
        if (value < min || value > max || (value & (value - 1)) != 0) return -1;
        return Integer.numberOfTrailingZeros(value) - Integer.numberOfTrailingZeros(min);
    }

    /**
     * Set the internal averaging ({@code AV_CONF}).
     *
     * @param temperatureSamples 2, 4, 8, 16, 32, 64, 128 or 256
     * @param humiditySamples    4, 8, 16, 32, 64, 128, 256 or 512
     * @throws IOException on bus error
     * @throws IllegalArgumentException for any other value
     */
    public void setAveraging(int temperatureSamples, int humiditySamples) throws IOException {
        int t = log2Index(temperatureSamples, 2, 256);
        int h = log2Index(humiditySamples, 4, 512);
        if (t < 0) throw new IllegalArgumentException("HTS221: temperatureSamples must be a power of two, 2..256");
        if (h < 0) throw new IllegalArgumentException("HTS221: humiditySamples must be a power of two, 4..512");
        writeReg(REG_AV_CONF, (t << 3) | h);
    }

    /**
     * Read the internal averaging.
     *
     * @return {temperature samples, humidity samples}
     * @throws IOException on bus error
     */
    public int[] getAveraging() throws IOException {
        int v = readReg(REG_AV_CONF);
        return new int[]{2 << ((v >> 3) & 0x07), 4 << (v & 0x07)};
    }

    /**
     * Enable or disable block data update ({@code CTRL_REG1.BDU}).
     *
     * @param enabled true to keep the output registers stable between reading LSB and MSB
     * @throws IOException on bus error
     */
    public void setBlockDataUpdate(boolean enabled) throws IOException {
        int ctrl1 = readReg(REG_CTRL_REG1);
        writeReg(REG_CTRL_REG1, enabled ? (ctrl1 | CTRL1_BDU) : (ctrl1 & ~CTRL1_BDU));
    }

    /**
     * Enter power-down mode ({@code CTRL_REG1.PD = 0}).
     *
     * @throws IOException on bus error
     */
    public void powerDown() throws IOException {
        writeReg(REG_CTRL_REG1, readReg(REG_CTRL_REG1) & ~CTRL1_PD);
    }

    /**
     * Leave power-down mode ({@code CTRL_REG1.PD = 1}).
     *
     * @throws IOException on bus error
     */
    public void powerUp() throws IOException {
        writeReg(REG_CTRL_REG1, readReg(REG_CTRL_REG1) | CTRL1_PD);
    }

    /**
     * Switch the internal heater on or off. Do not measure while it is on.
     *
     * @param on true to heat
     * @throws IOException on bus error
     */
    public void setHeater(boolean on) throws IOException {
        int ctrl2 = readReg(REG_CTRL_REG2);
        writeReg(REG_CTRL_REG2, on ? (ctrl2 | CTRL2_HEATER) : (ctrl2 & ~CTRL2_HEATER));
    }

    /**
     * Read the heater state.
     *
     * @return true when the heater is on
     * @throws IOException on bus error
     */
    public boolean isHeaterOn() throws IOException {
        return (readReg(REG_CTRL_REG2) & CTRL2_HEATER) != 0;
    }

    /**
     * Reload the trimming registers ({@code CTRL_REG2.BOOT}), wait for it to clear (timeout 100 ms)
     * and re-read the calibration.
     *
     * @throws IOException on bus error or when BOOT does not clear in time
     */
    public void reboot() throws IOException {
        int ctrl2 = readReg(REG_CTRL_REG2);
        writeReg(REG_CTRL_REG2, ctrl2 | CTRL2_BOOT);
        for (int i = 0; i < TIMEOUT_MS; i++) {
            if ((readReg(REG_CTRL_REG2) & CTRL2_BOOT) == 0) {
                loadCalibration();
                return;
            }
            sleepMs(1);
        }
        throw new IOException("HTS221 reboot timeout");
    }

    /**
     * Read the {@code WHO_AM_I} register.
     *
     * @return the device ID (0xBC for the HTS221)
     * @throws IOException on bus error
     */
    public int whoAmI() throws IOException {
        return readReg(REG_WHO_AM_I);
    }

    /**
     * Read {@code STATUS_REG}.
     *
     * @return raw status ({@link #STATUS_H_DA} / {@link #STATUS_T_DA})
     * @throws IOException on bus error
     */
    public int status() throws IOException {
        return readReg(REG_STATUS);
    }

    /**
     * Check whether new humidity and temperature data are both available.
     *
     * @return true when H_DA and T_DA are both set
     * @throws IOException on bus error
     */
    public boolean dataReady() throws IOException {
        return (status() & STATUS_BOTH) == STATUS_BOTH;
    }

    /**
     * Decoded calibration coefficients read at construction (and after {@link #reboot()}).
     *
     * @return {H0_rH, H1_rH, T0_degC, T1_degC, H0_T0_OUT, H1_T0_OUT, T0_OUT, T1_OUT}
     */
    public double[] readCalibration() {
        return new double[]{h0rH, h1rH, t0degC, t1degC, h0t0Out, h1t0Out, t0Out, t1Out};
    }

    /**
     * Configure the DRDY output pin ({@code CTRL_REG3}).
     *
     * @param enabled    route data-ready to the DRDY pin
     * @param activeHigh false selects active-low polarity
     * @param openDrain  true selects an open-drain output (default push-pull)
     * @throws IOException on bus error
     */
    public void configureDrdy(boolean enabled, boolean activeHigh, boolean openDrain) throws IOException {
        int v = 0;
        if (!activeHigh) v |= 0x80;
        if (openDrain) v |= 0x40;
        if (enabled) v |= 0x04;
        writeReg(REG_CTRL_REG3, v);
    }

    /**
     * Subscribe to data-ready events.
     *
     * <p>Enables DRDY (active high, push-pull). Delivery uses the connection's {@code intPin()}
     * (rising edge) when one is wired and falls back to a polling thread at the output data rate
     * otherwise. The callback receives {@code STATUS_REG}.
     *
     * @param callback called with the status
     * @throws IOException on bus error
     */
    public void onInterrupt(IntConsumer callback) throws IOException {
        offInterrupt();
        configureDrdy(true, true, false);
        this.callback = callback;
        InputPin pin = connection.intPin();
        if (pin != null) {
            intPin = pin;
            pin.onEdge(edgeHandler, EdgeTrigger.RISING);
        } else {
            startPolling();
        }
    }

    /**
     * Unsubscribe from data-ready events and disable the DRDY output.
     *
     * @throws IOException on bus error
     */
    public void offInterrupt() throws IOException {
        boolean had = callback != null;
        callback = null;
        if (intPin != null) {
            intPin.offEdge(edgeHandler);
            intPin = null;
        }
        stopPolling();
        if (had) configureDrdy(false, true, false);
    }

    /**
     * Read {@code STATUS_REG}; the flags clear when the output registers are read.
     *
     * @return raw status ({@link #STATUS_H_DA} / {@link #STATUS_T_DA})
     * @throws IOException on bus error
     */
    public int pollInterrupt() throws IOException {
        return status();
    }

    private void startPolling() {
        polling = true;
        pollThread = new Thread(() -> {
            while (polling) {
                try {
                    if (dataReady()) handleEdge();
                    double hz = getOutputDataRate();
                    Thread.sleep(hz > 0 ? (long) (1000 / hz) : 100);
                } catch (IOException e) {
                    // bus error; try again on the next tick
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "hts221-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void stopPolling() {
        polling = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    private void handleEdge() {
        try {
            int status = pollInterrupt();
            IntConsumer cb = callback;
            if (cb != null) cb.accept(status);
        } catch (IOException ignored) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }
}
