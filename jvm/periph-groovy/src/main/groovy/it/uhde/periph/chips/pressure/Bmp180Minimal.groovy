package it.uhde.periph.chips.pressure

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport
import java.io.IOException

/**
 * BMP180 — digital barometric pressure and temperature sensor (minimal driver).
 *
 * <p>Reads temperature and pressure via I²C using Bosch's integer compensation
 * algorithm. Calibration coefficients are loaded from the chip's EEPROM during
 * construction and sanity-checked. The chip ID register is verified to be 0x55.
 *
 * <p>Fixed I²C address: 0x77 (hardware-defined, not configurable).
 *
 * <p>Default oversampling setting (OSS): 0 (ultra-low-power).
 */
@CompileStatic
class Bmp180Minimal {

    static final int REG_CAL_START = 0xAA
    static final int REG_ID        = 0xD0
    static final int REG_SOFT_RST  = 0xE0
    static final int REG_CTRL_MEAS = 0xF4
    static final int REG_OUT_MSB   = 0xF6
    static final int CMD_TEMP      = 0x2E
    static final int CMD_PRES_OSS  = 0x34
    static final int CHIP_ID       = 0x55

    protected final Transport transport

    // Calibration coefficients (signed unless noted)
    protected int AC1, AC2, AC3
    protected int AC4, AC5, AC6   // unsigned uint16 — mask with 0xFFFF when needed
    protected int B1, B2
    protected int MB, MC, MD

    /** Current oversampling setting (0–3). */
    protected int oss = 0

    /**
     * Construct the driver, verify the chip ID, and load calibration data.
     *
     * @param transport I²C transport bound to address 0x77
     * @throws IOException on I²C error, wrong chip ID, or invalid calibration
     */
    Bmp180Minimal(Transport transport) {
        this.transport = transport

        byte[] id = transport.writeRead([(byte) REG_ID] as byte[], 1)
        if ((id[0] & 0xFF) != CHIP_ID) {
            throw new IOException(
                "BMP180 not found: expected chip ID 0x55, got 0x" +
                Integer.toHexString(id[0] & 0xFF))
        }

        readCalibration()
    }

    /**
     * Read and unpack the 22-byte calibration block from EEPROM (0xAA–0xBF).
     *
     * @throws IOException on I²C error or invalid calibration data
     */
    protected void readCalibration() {
        byte[] cal = transport.writeRead([(byte) REG_CAL_START] as byte[], 22)

        AC1 = (short)(((cal[0]  & 0xFF) << 8) | (cal[1]  & 0xFF))
        AC2 = (short)(((cal[2]  & 0xFF) << 8) | (cal[3]  & 0xFF))
        AC3 = (short)(((cal[4]  & 0xFF) << 8) | (cal[5]  & 0xFF))
        AC4 =          ((cal[6]  & 0xFF) << 8) | (cal[7]  & 0xFF)   // uint16
        AC5 =          ((cal[8]  & 0xFF) << 8) | (cal[9]  & 0xFF)   // uint16
        AC6 =          ((cal[10] & 0xFF) << 8) | (cal[11] & 0xFF)   // uint16
        B1  = (short)(((cal[12] & 0xFF) << 8) | (cal[13] & 0xFF))
        B2  = (short)(((cal[14] & 0xFF) << 8) | (cal[15] & 0xFF))
        MB  = (short)(((cal[16] & 0xFF) << 8) | (cal[17] & 0xFF))
        MC  = (short)(((cal[18] & 0xFF) << 8) | (cal[19] & 0xFF))
        MD  = (short)(((cal[20] & 0xFF) << 8) | (cal[21] & 0xFF))

        checkCalibration()
    }

    /**
     * Sanity-check: no coefficient may be 0x0000 or 0xFFFF.
     *
     * @throws IOException if any coefficient is 0x0000 or 0xFFFF
     */
    protected void checkCalibration() {
        int[] raw16 = [
            AC1 & 0xFFFF, AC2 & 0xFFFF, AC3 & 0xFFFF,
            AC4 & 0xFFFF, AC5 & 0xFFFF, AC6 & 0xFFFF,
            B1  & 0xFFFF, B2  & 0xFFFF,
            MB  & 0xFFFF, MC  & 0xFFFF, MD  & 0xFFFF
        ] as int[]
        String[] names = ['AC1','AC2','AC3','AC4','AC5','AC6','B1','B2','MB','MC','MD'] as String[]
        for (int i = 0; i < raw16.length; i++) {
            if (raw16[i] == 0x0000 || raw16[i] == 0xFFFF) {
                throw new IOException(
                    "BMP180 calibration invalid: ${names[i]} = 0x${Integer.toHexString(raw16[i])}")
            }
        }
    }

    /**
     * Trigger a temperature measurement and return the raw ADC value (UT).
     *
     * @return unsigned 16-bit raw temperature
     * @throws IOException on I²C error
     */
    protected int readRawTemperature() {
        transport.write([(byte) REG_CTRL_MEAS, (byte) CMD_TEMP] as byte[])
        Thread.sleep(5)
        byte[] b = transport.writeRead([(byte) REG_OUT_MSB] as byte[], 2)
        return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF)
    }

    /**
     * Trigger a pressure measurement and return the raw ADC value (UP).
     *
     * @param ossMode oversampling setting (0–3)
     * @return raw pressure ADC value (shifted by oss)
     * @throws IOException on I²C error
     */
    protected long readRawPressure(int ossMode) {
        transport.write([(byte) REG_CTRL_MEAS, (byte)(CMD_PRES_OSS | (ossMode << 6))] as byte[])
        Thread.sleep((long)(ossMode * 10L + 5L))
        byte[] b = transport.writeRead([(byte) REG_OUT_MSB] as byte[], 3)
        long raw = (((long)(b[0] & 0xFF)) << 16) | (((long)(b[1] & 0xFF)) << 8) | ((long)(b[2] & 0xFF))
        return raw >> (8 - ossMode)
    }

    /**
     * Compute B5 from a raw temperature value (UT).
     *
     * @param UT raw temperature ADC value
     * @return B5 (shared intermediate used by both temperature and pressure compensation)
     */
    protected long computeB5(int UT) {
        long X1 = (((long) UT - (long) AC6) * (long) AC5) >> 15
        long X2 = (((long) MC << 11) / (X1 + (long) MD)).toLong()
        return X1 + X2
    }

    /**
     * Read the temperature.
     *
     * <p>Triggers a temperature measurement, runs Bosch integer compensation,
     * and returns the result in degrees Celsius.
     *
     * @return temperature in °C
     * @throws IOException on I²C error
     */
    double temperature() {
        int  UT = readRawTemperature()
        long B5 = computeB5(UT)
        long T  = (B5 + 8L) >> 4
        return T / 10.0
    }

    /**
     * Read the pressure.
     *
     * <p>Re-reads temperature internally to refresh B5, then triggers a pressure
     * measurement. Runs Bosch integer compensation and returns the result in hPa.
     *
     * @return pressure in hPa
     * @throws IOException on I²C error
     */
    double pressure() {
        int  UT = readRawTemperature()
        long B5 = computeB5(UT)
        long UP = readRawPressure(oss)

        long B6 = B5 - 4000L
        long X1 = ((long) B2 * ((B6 * B6) >> 12)) >> 11
        long X2 = ((long) AC2 * B6) >> 11
        long X3 = X1 + X2
        long B3 = ((((long) AC1 * 4L + X3) << oss) + 2L) >> 2

        X1 = ((long) AC3 * B6) >> 13
        X2 = ((long) B1 * ((B6 * B6) >> 12)) >> 16
        X3 = ((X1 + X2) + 2L) >> 2
        long B4 = ((long)(AC4 & 0xFFFFFFFFL)) * (X3 + 32768L) >>> 15
        long B7 = (UP - B3) * (50000L >> oss)

        long p
        if (B7 < 0x80000000L) {
            p = ((B7 * 2L) / B4).toLong()
        } else {
            p = ((B7 / B4) * 2L).toLong()
        }

        X1 = (p >> 8) * (p >> 8)
        X1 = (X1 * 3038L) >> 16
        X2 = (-7357L * p) >> 16
        p  = p + ((X1 + X2 + 3791L) >> 4)

        return p / 100.0
    }
}
