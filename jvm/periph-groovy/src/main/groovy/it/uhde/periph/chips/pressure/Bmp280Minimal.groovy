package it.uhde.periph.chips.pressure

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BMP280 — digital barometric pressure and temperature sensor (minimal driver).
 *
 * <p>Reads temperature and pressure via I²C using Bosch's 64-bit integer
 * compensation algorithm. Calibration coefficients are loaded from the chip's
 * NVM during construction. The chip ID register is verified to be 0x58.
 *
 * <p>Configurable I²C address: 0x76 (SDO low, default) or 0x77 (SDO high).
 *
 * <p>Default settings: osrs_t=×1, osrs_p=×1, filter off; measurements are
 * triggered in forced mode (one shot per call).
 */
@CompileStatic
class Bmp280Minimal {

    static final int REG_CALIB     = 0x88
    static final int REG_ID        = 0xD0
    static final int REG_SOFT_RST  = 0xE0
    static final int REG_STATUS    = 0xF3
    static final int REG_CTRL_MEAS = 0xF4
    static final int REG_CONFIG    = 0xF5
    static final int REG_DATA      = 0xF7
    static final int CHIP_ID       = 0x58

    protected final Transport transport

    // Calibration coefficients
    protected int digT1   // uint16
    protected int digT2   // int16
    protected int digT3   // int16
    protected int digP1   // uint16
    protected int digP2   // int16
    protected int digP3   // int16
    protected int digP4   // int16
    protected int digP5   // int16
    protected int digP6   // int16
    protected int digP7   // int16
    protected int digP8   // int16
    protected int digP9   // int16

    /** tFine shared between temperature and pressure compensation. */
    protected int tFine

    /** ctrl_meas value applied at each measurement. */
    protected int ctrlMeas = 0x25   // osrs_t=×1, osrs_p=×1, mode=forced
    /** config register value. */
    protected int config   = 0x00   // filter off

    /**
     * Construct the driver at the default address (0x76), verify the chip ID,
     * and load calibration data.
     *
     * @param transport I²C transport bound to address 0x76
     * @throws IOException on I²C error or wrong chip ID
     */
    Bmp280Minimal(Transport transport) {
        this(transport, 0x76)
    }

    /**
     * Construct the driver at the given address, verify the chip ID, and load
     * calibration data.
     *
     * @param transport I²C transport bound to the given address
     * @param addr      I²C device address (0x76 or 0x77)
     * @throws IOException on I²C error or wrong chip ID
     */
    Bmp280Minimal(Transport transport, int addr) {
        this.transport = transport

        byte[] id = transport.writeRead([(byte) REG_ID] as byte[], 1)
        if ((id[0] & 0xFF) != CHIP_ID) {
            throw new IOException(
                "BMP280 not found: expected chip ID 0x58, got 0x" +
                Integer.toHexString(id[0] & 0xFF))
        }

        readCalibration()
    }

    /**
     * Read and unpack the 24-byte calibration block from NVM (0x88–0x9F).
     *
     * <p>Calibration is little-endian: LSB comes first. T1 and P1 are unsigned
     * (uint16); all other coefficients are signed (int16).
     *
     * @throws IOException on I²C error
     */
    protected void readCalibration() {
        byte[] cal = transport.writeRead([(byte) REG_CALIB] as byte[], 24)
        ByteBuffer buf = ByteBuffer.wrap(cal).order(ByteOrder.LITTLE_ENDIAN)

        digT1 = buf.getShort() & 0xFFFF   // uint16
        digT2 = (int) buf.getShort()       // int16
        digT3 = (int) buf.getShort()       // int16
        digP1 = buf.getShort() & 0xFFFF   // uint16
        digP2 = (int) buf.getShort()       // int16
        digP3 = (int) buf.getShort()       // int16
        digP4 = (int) buf.getShort()       // int16
        digP5 = (int) buf.getShort()       // int16
        digP6 = (int) buf.getShort()       // int16
        digP7 = (int) buf.getShort()       // int16
        digP8 = (int) buf.getShort()       // int16
        digP9 = (int) buf.getShort()       // int16
    }

    /**
     * Trigger a forced-mode measurement and burst-read 6 bytes from 0xF7.
     *
     * @return 6-byte raw data array: [press_msb, press_lsb, press_xlsb,
     *         temp_msb, temp_lsb, temp_xlsb]
     * @throws IOException on I²C error
     */
    protected byte[] readRawData() {
        transport.write([(byte) REG_CTRL_MEAS, (byte)((ctrlMeas & 0xFC) | 0x01)] as byte[])
        Thread.sleep(7)
        return transport.writeRead([(byte) REG_DATA] as byte[], 6)
    }

    /**
     * Compute temperature compensation and update tFine.
     *
     * @param adcT raw 20-bit temperature ADC value
     * @return temperature in °C
     */
    protected double compensateTemperature(int adcT) {
        long var1 = (((long) adcT >> 3) - ((long) digT1 << 1)) * (long) digT2 >> 11
        long var2 = ((((long) adcT >> 4) - (long) digT1) *
                     (((long) adcT >> 4) - (long) digT1) >> 12) * (long) digT3 >> 14
        tFine = (int)(var1 + var2)
        return ((tFine * 5L + 128L) >> 8) / 100.0
    }

    /**
     * Compute pressure compensation using the current tFine value.
     *
     * @param adcP raw 20-bit pressure ADC value
     * @return pressure in hPa
     */
    protected double compensatePressure(int adcP) {
        long var1 = (long) tFine - 128000L
        long var2 = var1 * var1 * (long) digP6
        var2 = var2 + ((var1 * (long) digP5) << 17)
        var2 = var2 + ((long) digP4 << 35)
        var1 = ((var1 * var1 * (long) digP3) >> 8) + ((var1 * (long) digP2) << 12)
        var1 = ((1L << 47) + var1) * ((long) digP1 & 0xFFFFL) >> 33
        if (var1 == 0L) return 0.0
        long p = 1048576L - (long) adcP
        p = ((p << 31) - var2) * 3125L / var1
        var1 = ((long) digP9 * (p >> 13) * (p >> 13)) >> 25
        var2 = ((long) digP8 * p) >> 19
        p = ((p + var1 + var2) >> 8) + ((long) digP7 << 4)
        return (p / 256.0) / 100.0
    }

    /**
     * Read the temperature.
     *
     * <p>Triggers a forced-mode measurement, runs Bosch 64-bit integer
     * compensation, and returns the result in degrees Celsius.
     *
     * @return temperature in °C
     * @throws IOException on I²C error
     */
    double temperature() {
        byte[] raw = readRawData()
        int adcT = ((raw[3] & 0xFF) << 12) | ((raw[4] & 0xFF) << 4) | ((raw[5] & 0xFF) >> 4)
        return compensateTemperature(adcT)
    }

    /**
     * Read the pressure.
     *
     * <p>Triggers a forced-mode measurement, compensates temperature first
     * (to populate tFine), then compensates pressure. Returns the result in hPa.
     *
     * @return pressure in hPa
     * @throws IOException on I²C error
     */
    double pressure() {
        byte[] raw = readRawData()
        int adcP = ((raw[0] & 0xFF) << 12) | ((raw[1] & 0xFF) << 4) | ((raw[2] & 0xFF) >> 4)
        int adcT = ((raw[3] & 0xFF) << 12) | ((raw[4] & 0xFF) << 4) | ((raw[5] & 0xFF) >> 4)
        compensateTemperature(adcT)   // populates tFine
        return compensatePressure(adcP)
    }
}
