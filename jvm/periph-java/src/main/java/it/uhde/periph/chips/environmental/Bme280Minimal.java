package it.uhde.periph.chips.environmental;

import it.uhde.periph.transport.Transport;

import java.io.IOException;

/**
 * BME280 — combined humidity + pressure + temperature sensor (minimal driver).
 *
 * <p>Reads calibrated temperature (°C), pressure (hPa), and humidity (%RH) via
 * I²C. The chip ID register is verified to be {@code 0x60}.
 *
 * <p>Sibling of the BMP280 driver: register-compatible for pressure and
 * temperature, plus an integrated humidity front-end (its own calibration
 * block, control register, output registers, and compensation formula).
 *
 * <p>Default settings: osrs_t=×1, osrs_p=×1, osrs_h=×1, IIR filter off,
 * forced mode.
 */
public class Bme280Minimal {

    // Register addresses
    protected static final int REG_CALIB      = 0x88;
    protected static final int REG_H1         = 0xA1;
    protected static final int REG_ID         = 0xD0;
    protected static final int REG_SOFT_RST   = 0xE0;
    protected static final int REG_CAL_H2     = 0xE1;
    protected static final int REG_CTRL_HUM   = 0xF2;
    protected static final int REG_STATUS     = 0xF3;
    protected static final int REG_CTRL_MEAS  = 0xF4;
    protected static final int REG_CONFIG     = 0xF5;
    protected static final int REG_DATA       = 0xF7;

    protected static final int CHIP_ID      = 0x60;
    protected static final int RESET_CMD    = 0xB6;
    protected static final int MEAS_TIME_MS = 9;

    protected final Transport transport;

    // Calibration coefficients
    protected int digT1;   // uint16
    protected int digT2;   // int16
    protected int digT3;   // int16
    protected int digP1;   // uint16
    protected int digP2;   // int16
    protected int digP3;   // int16
    protected int digP4;   // int16
    protected int digP5;   // int16
    protected int digP6;   // int16
    protected int digP7;   // int16
    protected int digP8;   // int16
    protected int digP9;   // int16
    protected int digH1;   // uint8
    protected int digH2;   // int16
    protected int digH3;   // uint8
    protected int digH4;   // int16 (12-bit sign-extended)
    protected int digH5;   // int16 (12-bit sign-extended)
    protected int digH6;   // int8

    /** tFine shared between temperature, pressure, and humidity compensation. */
    protected int tFine;

    /** ctrl_hum value applied at each measurement. */
    protected int ctrlHum  = 0x01;
    /** ctrl_meas value applied at each measurement. */
    protected int ctrlMeas = 0x25;
    /** config register value. */
    protected int config   = 0x00;

    /**
     * Construct the driver, verify the chip ID, and load calibration data.
     *
     * @param transport I²C transport bound to the BME280 address (0x76 or 0x77)
     * @throws IOException on I²C error, wrong chip ID, or invalid calibration
     */
    public Bme280Minimal(Transport transport) throws IOException {
        this(transport, 0x76);
    }

    /**
     * Construct the driver at the given address, verify the chip ID, and load
     * calibration data.
     *
     * @param transport I²C transport bound to the given address
     * @param addr      I²C device address (0x76 or 0x77)
     * @throws IOException on I²C error, wrong chip ID, or invalid calibration
     */
    public Bme280Minimal(Transport transport, int addr) throws IOException {
        this.transport = transport;

        byte[] id = transport.writeRead(new byte[]{(byte) REG_ID}, 1);
        int chipId = id[0] & 0xFF;
        if (chipId != CHIP_ID) {
            throw new IOException(
                    "BME280 not found: expected 0x60, got 0x"
                    + Integer.toHexString(chipId));
        }

        readCalibration();

        transport.write(new byte[]{(byte) REG_CTRL_HUM, (byte) ctrlHum});
        transport.write(new byte[]{(byte) REG_CTRL_MEAS, (byte) ctrlMeas});
        transport.write(new byte[]{(byte) REG_CONFIG, (byte) config});
    }

    /**
     * Read and unpack the 33-byte calibration block from NVM (26 bytes from
     * 0x88 plus 7 bytes from 0xE1).
     *
     * <p>Calibration is little-endian for the 16-bit fields. The humidity
     * block 0xE1–0xE7 packs dig_H4 / dig_H5 into register 0xE5 (lower / upper
     * nibble); both are 12-bit signed values sign-extended to 16-bit.
     *
     * @throws IOException on I²C error
     */
    protected void readCalibration() throws IOException {
        byte[] cal = transport.writeRead(new byte[]{(byte) REG_CALIB}, 26);
        digT1 = ((cal[1] & 0xFF) << 8) | (cal[0] & 0xFF);
        digT2 = (short) (((cal[3] & 0xFF) << 8) | (cal[2] & 0xFF));
        digT3 = (short) (((cal[5] & 0xFF) << 8) | (cal[4] & 0xFF));
        digP1 = ((cal[7] & 0xFF) << 8) | (cal[6] & 0xFF);
        digP2 = (short) (((cal[9] & 0xFF) << 8) | (cal[8] & 0xFF));
        digP3 = (short) (((cal[11] & 0xFF) << 8) | (cal[10] & 0xFF));
        digP4 = (short) (((cal[13] & 0xFF) << 8) | (cal[12] & 0xFF));
        digP5 = (short) (((cal[15] & 0xFF) << 8) | (cal[14] & 0xFF));
        digP6 = (short) (((cal[17] & 0xFF) << 8) | (cal[16] & 0xFF));
        digP7 = (short) (((cal[19] & 0xFF) << 8) | (cal[18] & 0xFF));
        digP8 = (short) (((cal[21] & 0xFF) << 8) | (cal[20] & 0xFF));
        digP9 = (short) (((cal[23] & 0xFF) << 8) | (cal[22] & 0xFF));
        digH1 = cal[25] & 0xFF;

        byte[] h = transport.writeRead(new byte[]{(byte) REG_CAL_H2}, 7);
        digH2 = (short) (((h[1] & 0xFF) << 8) | (h[0] & 0xFF));
        digH3 = h[2] & 0xFF;
        int h4raw = ((h[3] & 0xFF) << 4) | (h[4] & 0x0F);
        int h5raw = ((h[5] & 0xFF) << 4) | ((h[4] >> 4) & 0x0F);
        digH4 = (h4raw & 0x800) != 0 ? (short) (h4raw | 0xF000) : (short) h4raw;
        digH5 = (h5raw & 0x800) != 0 ? (short) (h5raw | 0xF000) : (short) h5raw;
        digH6 = (byte) h[6];
    }

    /**
     * Trigger a forced-mode measurement and burst-read 8 bytes from 0xF7.
     *
     * <p>Writes ctrl_hum (must precede ctrl_meas for humidity oversampling to
     * latch), then writes ctrl_meas with forced mode, waits 9 ms, and reads
     * press[19:0], temp[19:0], and hum[15:0] in a single burst.
     *
     * @return 8-byte raw data array: [press_msb, press_lsb, press_xlsb,
     *         temp_msb, temp_lsb, temp_xlsb, hum_msb, hum_lsb]
     * @throws IOException on I²C error
     */
    protected byte[] triggerAndRead() throws IOException {
        transport.write(new byte[]{(byte) REG_CTRL_HUM, (byte) ctrlHum});
        transport.write(new byte[]{(byte) REG_CTRL_MEAS, (byte) ((ctrlMeas & 0xFC) | 0x01)});
        try { Thread.sleep(MEAS_TIME_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return transport.writeRead(new byte[]{(byte) REG_DATA}, 8);
    }

    /**
     * Compute temperature compensation and update tFine.
     *
     * @param adcT raw 20-bit temperature ADC value
     * @return temperature in °C
     */
    protected double compensateTemperature(int adcT) {
        long var1 = (((long) adcT >> 3) - ((long) digT1 << 1)) * (long) digT2 >> 11;
        long var2 = ((((long) adcT >> 4) - (long) (digT1 & 0xFFFFL))
                   * (((long) adcT >> 4) - (long) (digT1 & 0xFFFFL)) >> 12)
                   * (long) digT3 >> 14;
        tFine = (int) (var1 + var2);
        return ((tFine * 5 + 128) >> 8) / 100.0;
    }

    /**
     * Compute pressure compensation using the current tFine value.
     *
     * @param adcP raw 20-bit pressure ADC value
     * @return pressure in hPa
     */
    protected double compensatePressure(int adcP) {
        long var1 = (long) tFine - 128000L;
        long var2 = var1 * var1 * (long) digP6;
        var2 = var2 + ((var1 * (long) digP5) << 17);
        var2 = var2 + ((long) digP4 << 35);
        var1 = ((var1 * var1 * (long) digP3) >> 8) + ((var1 * (long) digP2) << 12);
        var1 = ((1L << 47) + var1) * ((long) digP1 & 0xFFFFL) >> 33;
        if (var1 == 0) return 0.0;
        long p = 1048576L - (long) adcP;
        p = ((p << 31) - var2) * 3125L / var1;
        var1 = ((long) digP9 * (p >> 13) * (p >> 13)) >> 25;
        var2 = ((long) digP8 * p) >> 19;
        p = ((p + var1 + var2) >> 8) + ((long) digP7 << 4);
        return (p / 256.0) / 100.0;
    }

    /**
     * Compute humidity compensation using the current tFine value.
     *
     * @param adcH raw 16-bit humidity ADC value
     * @return humidity in %RH
     */
    protected double compensateHumidity(int adcH) {
        long v = (long) tFine - 76800L;
        long vX1 = (((long) adcH << 14) - ((long) digH4 << 20) - ((long) digH5 * v) + 16384) >> 15;
        v = ((v * (long) digH6) >> 10) * (((v * (long) digH3) >> 11) + 32768L);
        v = v >> 10;
        v = v + 2097152L;
        v = ((v * (long) digH2) + 8192L) >> 14;
        v = vX1 * v;
        long vX2 = (v >> 15) * (v >> 15);
        v = v - (((vX2 >> 7) * (long) digH1) >> 4);
        if (v < 0) v = 0;
        if (v > 419430400L) v = 419430400L;
        return (v >> 12) / 1024.0;
    }

    /**
     * Read the temperature.
     *
     * <p>Triggers a forced-mode measurement and returns the result in degrees
     * Celsius. Also updates tFine for subsequent pressure/humidity reads.
     *
     * @return temperature in °C
     * @throws IOException on I²C error
     */
    public double temperature() throws IOException {
        byte[] raw = triggerAndRead();
        int adcT = ((raw[3] & 0xFF) << 12) | ((raw[4] & 0xFF) << 4) | ((raw[5] & 0xFF) >> 4);
        return compensateTemperature(adcT);
    }

    /**
     * Read the pressure.
     *
     * <p>Triggers a forced-mode measurement, compensates temperature first (to
     * populate tFine), then compensates pressure. Returns the result in hPa.
     *
     * @return pressure in hPa
     * @throws IOException on I²C error
     */
    public double pressure() throws IOException {
        byte[] raw = triggerAndRead();
        int adcP = ((raw[0] & 0xFF) << 12) | ((raw[1] & 0xFF) << 4) | ((raw[2] & 0xFF) >> 4);
        int adcT = ((raw[3] & 0xFF) << 12) | ((raw[4] & 0xFF) << 4) | ((raw[5] & 0xFF) >> 4);
        compensateTemperature(adcT);
        return compensatePressure(adcP);
    }

    /**
     * Read the humidity.
     *
     * <p>Triggers a forced-mode measurement, compensates temperature first (to
     * populate tFine), then compensates humidity. Returns the result in %RH.
     *
     * @return relative humidity in %RH
     * @throws IOException on I²C error
     */
    public double humidity() throws IOException {
        byte[] raw = triggerAndRead();
        int adcH = ((raw[6] & 0xFF) << 8) | (raw[7] & 0xFF);
        int adcT = ((raw[3] & 0xFF) << 12) | ((raw[4] & 0xFF) << 4) | ((raw[5] & 0xFF) >> 4);
        compensateTemperature(adcT);
        return compensateHumidity(adcH);
    }
}
