package it.uhde.periph.chips.environmental

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport
import java.io.IOException

/**
 * BME280 — combined humidity + pressure + temperature sensor (minimal driver).
 *
 * <p>Reads calibrated temperature (°C), pressure (hPa), and humidity (%RH) via
 * I²C. The chip ID register is verified to be 0x60.
 *
 * <p>Sibling of the BMP280 driver: register-compatible for pressure and
 * temperature, plus an integrated humidity front-end (its own calibration
 * block, control register, output registers, and compensation formula).
 *
 * <p>Default settings: osrs_t=×1, osrs_p=×1, osrs_h=×1, IIR filter off,
 * forced mode.
 */
@CompileStatic
class Bme280Minimal {

    static final int REG_CALIB       = 0x88
    static final int REG_H1          = 0xA1
    static final int REG_ID          = 0xD0
    static final int REG_RESET       = 0xE0
    static final int REG_CAL_H2      = 0xE1
    static final int REG_CTRL_HUM    = 0xF2
    static final int REG_STATUS      = 0xF3
    static final int REG_CTRL_MEAS   = 0xF4
    static final int REG_CONFIG      = 0xF5
    static final int REG_DATA        = 0xF7

    static final int CHIP_ID         = 0x60
    static final int RESET_CMD       = 0xB6
    static final int MEAS_TIME_MS    = 9

    protected final Transport transport

    protected int digT1
    protected int digT2
    protected int digT3
    protected int digP1
    protected int digP2
    protected int digP3
    protected int digP4
    protected int digP5
    protected int digP6
    protected int digP7
    protected int digP8
    protected int digP9
    protected int digH1
    protected int digH2
    protected int digH3
    protected int digH4
    protected int digH5
    protected int digH6

    /** tFine shared between temperature, pressure, and humidity compensation. */
    protected int tFine

    protected int ctrlHum  = 0x01
    protected int ctrlMeas = 0x25
    protected int config   = 0x00

    Bme280Minimal(Transport transport) throws IOException {
        this(transport, 0x76)
    }

    Bme280Minimal(Transport transport, int addr) throws IOException {
        this.transport = transport

        byte[] id = transport.writeRead(new byte[]{(byte) REG_ID}, 1)
        int chipId = id[0] & 0xFF
        if (chipId != CHIP_ID) {
            throw new IOException("BME280 not found: expected 0x60, got 0x" + Integer.toHexString(chipId))
        }

        readCalibration()

        transport.write(new byte[]{(byte) REG_CTRL_HUM, (byte) ctrlHum})
        transport.write(new byte[]{(byte) REG_CTRL_MEAS, (byte) ctrlMeas})
        transport.write(new byte[]{(byte) REG_CONFIG, (byte) config})
    }

    protected void readCalibration() throws IOException {
        byte[] cal = transport.writeRead(new byte[]{(byte) REG_CALIB}, 26)
        digT1 = ((cal[1] & 0xFF) << 8) | (cal[0] & 0xFF)
        digT2 = (short) (((cal[3] & 0xFF) << 8) | (cal[2] & 0xFF))
        digT3 = (short) (((cal[5] & 0xFF) << 8) | (cal[4] & 0xFF))
        digP1 = ((cal[7] & 0xFF) << 8) | (cal[6] & 0xFF)
        digP2 = (short) (((cal[9] & 0xFF) << 8) | (cal[8] & 0xFF))
        digP3 = (short) (((cal[11] & 0xFF) << 8) | (cal[10] & 0xFF))
        digP4 = (short) (((cal[13] & 0xFF) << 8) | (cal[12] & 0xFF))
        digP5 = (short) (((cal[15] & 0xFF) << 8) | (cal[14] & 0xFF))
        digP6 = (short) (((cal[17] & 0xFF) << 8) | (cal[16] & 0xFF))
        digP7 = (short) (((cal[19] & 0xFF) << 8) | (cal[18] & 0xFF))
        digP8 = (short) (((cal[21] & 0xFF) << 8) | (cal[20] & 0xFF))
        digP9 = (short) (((cal[23] & 0xFF) << 8) | (cal[22] & 0xFF))
        digH1 = cal[25] & 0xFF

        byte[] h = transport.writeRead(new byte[]{(byte) REG_CAL_H2}, 7)
        digH2 = (short) (((h[1] & 0xFF) << 8) | (h[0] & 0xFF))
        digH3 = h[2] & 0xFF
        int h4raw = ((h[3] & 0xFF) << 4) | (h[4] & 0x0F)
        int h5raw = ((h[5] & 0xFF) << 4) | ((h[4] >> 4) & 0x0F)
        digH4 = (h4raw & 0x800) != 0 ? (short) (h4raw | 0xF000) : (short) h4raw
        digH5 = (h5raw & 0x800) != 0 ? (short) (h5raw | 0xF000) : (short) h5raw
        digH6 = (byte) h[6]
    }

    protected byte[] triggerAndRead() throws IOException {
        transport.write(new byte[]{(byte) REG_CTRL_HUM, (byte) ctrlHum})
        transport.write(new byte[]{(byte) REG_CTRL_MEAS, (byte) ((ctrlMeas & 0xFC) | 0x01)})
        try { Thread.sleep(MEAS_TIME_MS) } catch (InterruptedException e) { Thread.currentThread().interrupt() }
        return transport.writeRead(new byte[]{(byte) REG_DATA}, 8)
    }

    protected double compensateTemperature(int adcT) {
        long var1 = (((long) adcT >> 3) - ((long) digT1 << 1)) * (long) digT2 >> 11
        long var2 = ((((long) adcT >> 4) - (long) (digT1 & 0xFFFFL))
                   * (((long) adcT >> 4) - (long) (digT1 & 0xFFFFL)) >> 12)
                   * (long) digT3 >> 14
        tFine = (int) (var1 + var2)
        return ((tFine * 5 + 128) >> 8) / 100.0
    }

    protected double compensatePressure(int adcP) {
        long var1 = (long) tFine - 128000L
        long var2 = var1 * var1 * (long) digP6
        var2 = var2 + ((var1 * (long) digP5) << 17)
        var2 = var2 + ((long) digP4 << 35)
        var1 = ((var1 * var1 * (long) digP3) >> 8) + ((var1 * (long) digP2) << 12)
        var1 = ((1L << 47) + var1) * ((long) digP1 & 0xFFFFL) >> 33
        if (var1 == 0) return 0.0
        long p = 1048576L - (long) adcP
        p = ((p << 31) - var2) * 3125L / var1
        var1 = ((long) digP9 * (p >> 13) * (p >> 13)) >> 25
        var2 = ((long) digP8 * p) >> 19
        p = ((p + var1 + var2) >> 8) + ((long) digP7 << 4)
        return (p / 256.0) / 100.0
    }

    protected double compensateHumidity(int adcH) {
        long v = (long) tFine - 76800L
        long vX1 = (((long) adcH << 14) - ((long) digH4 << 20) - ((long) digH5 * v) + 16384) >> 15
        v = ((v * (long) digH6) >> 10) * (((v * (long) digH3) >> 11) + 32768L)
        v = v >> 10
        v = v + 2097152L
        v = ((v * (long) digH2) + 8192L) >> 14
        v = vX1 * v
        long vX2 = (v >> 15) * (v >> 15)
        v = v - (((vX2 >> 7) * (long) digH1) >> 4)
        if (v < 0) v = 0
        if (v > 419430400L) v = 419430400L
        return (v >> 12) / 1024.0
    }

    double temperature() throws IOException {
        byte[] raw = triggerAndRead()
        int adcT = ((raw[3] & 0xFF) << 12) | ((raw[4] & 0xFF) << 4) | ((raw[5] & 0xFF) >> 4)
        return compensateTemperature(adcT)
    }

    double pressure() throws IOException {
        byte[] raw = triggerAndRead()
        int adcP = ((raw[0] & 0xFF) << 12) | ((raw[1] & 0xFF) << 4) | ((raw[2] & 0xFF) >> 4)
        int adcT = ((raw[3] & 0xFF) << 12) | ((raw[4] & 0xFF) << 4) | ((raw[5] & 0xFF) >> 4)
        compensateTemperature(adcT)
        return compensatePressure(adcP)
    }

    double humidity() throws IOException {
        byte[] raw = triggerAndRead()
        int adcH = ((raw[6] & 0xFF) << 8) | (raw[7] & 0xFF)
        int adcT = ((raw[3] & 0xFF) << 12) | ((raw[4] & 0xFF) << 4) | ((raw[5] & 0xFF) >> 4)
        compensateTemperature(adcT)
        return compensateHumidity(adcH)
    }
}
