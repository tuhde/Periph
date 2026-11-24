package it.uhde.periph.chips.humidity

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Register
import it.uhde.periph.connection.RegisterConnection

/**
 * HTS221 — capacitive digital relative-humidity and temperature sensor (STMicroelectronics),
 * minimal driver.
 *
 * <p>The chip is factory calibrated: the constructor reads the per-device two-point calibration
 * (registers {@code 0x30}–{@code 0x3F}) and writes {@code CTRL_REG1 = 0x84} (active, block data
 * update, one-shot). {@link #read()} starts a one-shot conversion, waits for it (timeout 100 ms)
 * and converts the outputs by linear interpolation. I²C address {@code 0x5F} (fixed). Multi-byte
 * transfers set the sub-address MSb (register auto-increment), which the driver does itself.
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>Active mode, block data update on, one-shot (ODR = 00)</li>
 *   <li>Averaging at the power-on values (16 temperature / 32 humidity samples)</li>
 *   <li>Heater off, DRDY pin off</li>
 * </ul>
 */
@CompileStatic
class Hts221Minimal {

    protected static final int REG_WHO_AM_I      = 0x0F
    protected static final int REG_AV_CONF       = 0x10
    protected static final int REG_CTRL_REG1     = 0x20
    protected static final int REG_CTRL_REG2     = 0x21
    protected static final int REG_CTRL_REG3     = 0x22
    protected static final int REG_STATUS        = 0x27
    protected static final int REG_HUMIDITY_OUT  = 0x28
    protected static final int REG_CALIB         = 0x30

    protected static final int AUTO_INC          = 0x80
    protected static final int CTRL_REG1_DEFAULT = 0x84
    protected static final int CTRL1_PD          = 0x80
    protected static final int CTRL1_BDU         = 0x04
    protected static final int CTRL1_ODR_MASK    = 0x03
    protected static final int CTRL2_BOOT        = 0x80
    protected static final int CTRL2_HEATER      = 0x02
    protected static final int CTRL2_ONE_SHOT    = 0x01
    protected static final int STATUS_BOTH       = 0x03
    protected static final int TIMEOUT_MS        = 100

    protected final RegisterConnection connection

    protected double h0rH, h1rH, t0degC, t1degC
    protected int h0t0Out, h1t0Out, t0Out, t1Out

    /**
     * Construct the driver: read the calibration block and start one-shot mode.
     *
     * @param connection I²C register connection bound to 0x5F
     * @throws IOException on bus error
     */
    Hts221Minimal(RegisterConnection connection) {
        this.connection = connection
        loadCalibration()
        writeReg(REG_CTRL_REG1, CTRL_REG1_DEFAULT)
    }

    protected static void sleepMs(long ms) {
        try {
            Thread.sleep(ms)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
        }
    }

    protected void writeReg(int reg, int value) {
        connection.write(reg, [(byte) value] as byte[])
    }

    protected int readReg(int reg) {
        return connection.read(reg, 1)[0] & 0xFF
    }

    protected byte[] readBurst(int reg, int length) {
        return connection.read(reg | AUTO_INC, length)
    }

    private static int word(byte[] b, int i) {
        return Register.toSigned(((b[i + 1] & 0xFF) << 8) | (b[i] & 0xFF), 16)
    }

    protected void loadCalibration() {
        byte[] c = readBurst(REG_CALIB, 16)
        int msb = c[5] & 0xFF
        int t0 = ((msb & 0x03) << 8) | (c[2] & 0xFF)
        int t1 = ((msb & 0x0C) << 6) | (c[3] & 0xFF)
        h0rH = (c[0] & 0xFF) / 2.0d
        h1rH = (c[1] & 0xFF) / 2.0d
        t0degC = t0 / 8.0d
        t1degC = t1 / 8.0d
        h0t0Out = word(c, 6)
        h1t0Out = word(c, 10)
        t0Out = word(c, 12)
        t1Out = word(c, 14)
    }

    protected double humidityFromRaw(int raw) {
        double span = (double) h1t0Out - h0t0Out
        if (span == 0.0d) return 0.0d
        double v = ((double) raw - h0t0Out) * (h1rH - h0rH) / span + h0rH
        return Math.max(0.0d, Math.min(100.0d, v))
    }

    protected double temperatureFromRaw(int raw) {
        double span = (double) t1Out - t0Out
        if (span == 0.0d) return 0.0d
        return ((double) raw - t0Out) * (t1degC - t0degC) / span + t0degC
    }

    /** Burst-read the output registers: raw humidity and raw temperature, signed 16-bit. */
    protected int[] readRawPair() {
        byte[] d = readBurst(REG_HUMIDITY_OUT, 4)
        return [word(d, 0), word(d, 2)] as int[]
    }

    /** Start a one-shot conversion, wait for both flags and return the raw outputs. */
    protected int[] oneShotRaw() {
        int ctrl2 = readReg(REG_CTRL_REG2)
        writeReg(REG_CTRL_REG2, ctrl2 | CTRL2_ONE_SHOT)
        int status = 0
        for (int i = 0; i < TIMEOUT_MS; i++) {
            status = readReg(REG_STATUS)
            if ((status & STATUS_BOTH) == STATUS_BOTH) {
                return readRawPair()
            }
            sleepMs(1)
        }
        throw new IOException("HTS221 conversion timeout (STATUS=0x" + Integer.toHexString(status) + ")")
    }

    /**
     * Measure temperature and relative humidity (one-shot).
     *
     * @return {temperature in °C, relative humidity in %rH (clamped to 0–100)}
     * @throws IOException on bus error or when the conversion does not finish within 100 ms
     */
    double[] read() {
        int[] raw = oneShotRaw()
        return [temperatureFromRaw(raw[1]), humidityFromRaw(raw[0])] as double[]
    }
}
