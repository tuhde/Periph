package it.uhde.periph.chips.pressure

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * LPS28DFW — dual full-scale digital barometer (minimal driver).
 *
 * Reads absolute pressure and temperature from the STMicroelectronics
 * CCLGA-7L water-resistant sensor. I²C address is 0x5C (SA0=GND) or
 * 0x5D (SA0=VDD). Pressure is 24-bit signed, temperature is 16-bit signed.
 *
 * Default configuration (baked in at construction):
 *  - FS_MODE = 0 (Mode 1, 0–1260 hPa, 4096 LSB/hPa)
 *  - AVG = 0b010 (16 samples)
 *  - ODR = 0b0100 (25 Hz)
 *  - BDU = 1, EN_LPFP = 1, LFPF_CFG = 0 (ODR/4 bandwidth)
 */
@CompileStatic
class Lps28dfwMinimal {

    /** WHO_AM_I value: fixed device identifier for LPS28DFW. */
    public static final int CHIP_ID = 0xB4
    /** Full-scale mode 1: 0–1260 hPa, 4096 LSB/hPa. */
    public static final int FS_MODE_1 = 0
    /** Full-scale mode 2: 0–4060 hPa, 2048 LSB/hPa. */
    public static final int FS_MODE_2 = 1

    protected static final int REG_INTERRUPT_CFG = 0x0B
    protected static final int REG_WHO_AM_I      = 0x0F
    protected static final int REG_CTRL_REG1     = 0x10
    protected static final int REG_CTRL_REG2     = 0x11
    protected static final int REG_STATUS        = 0x27
    protected static final int REG_PRESS_OUT_XL  = 0x28
    protected static final int REG_PRESS_OUT_L   = 0x29
    protected static final int REG_PRESS_OUT_H   = 0x2A
    protected static final int REG_TEMP_OUT_L    = 0x2B
    protected static final int REG_TEMP_OUT_H    = 0x2C

    protected static final double SENSITIVITY_MODE1 = 4096.0d
    protected static final double SENSITIVITY_MODE2 = 2048.0d

    protected final Connection connection
    protected int fsMode = 0
    protected int odr = 0x04
    protected int avg = 0x02
    protected int lpfEn = 1
    protected int lpfCfg = 0
    protected int bdu = 1

    Lps28dfwMinimal(Connection connection) {
        this.connection = connection
        byte[] id = connection.writeRead([REG_WHO_AM_I] as byte[], 1)
        if ((id[0] & 0xFF) != CHIP_ID) {
            throw new IOException("LPS28DFW WHO_AM_I mismatch: expected 0x" +
                    Integer.toHexString(CHIP_ID) + ", got 0x" +
                    Integer.toHexString(id[0] & 0xFF))
        }
        try { Thread.sleep(2) } catch (InterruptedException ignored) { Thread.currentThread().interrupt() }
        int ctrl2 = (fsMode << 6) | (lpfCfg << 5) | (lpfEn << 4) | (bdu << 3)
        writeReg(REG_CTRL_REG2, ctrl2)
        int ctrl1 = (odr << 3) | (avg & 0x07)
        writeReg(REG_CTRL_REG1, ctrl1)
    }

    /** Write one byte to a register. */
    protected void writeReg(int reg, int value) {
        connection.write([(byte) reg, (byte) value] as byte[])
    }

    /** Read raw 24-bit pressure value (signed). */
    protected int readPressureRaw() {
        byte[] b = connection.writeRead([REG_PRESS_OUT_XL] as byte[], 3)
        int v = ((b[2] & 0xFF) << 16) | ((b[1] & 0xFF) << 8) | (b[0] & 0xFF)
        if ((v & 0x800000) != 0) v = (int) (v | 0xFF000000L)
        return v
    }

    /** Read raw 16-bit temperature value (signed). */
    protected int readTemperatureRaw() {
        byte[] b = connection.writeRead([REG_TEMP_OUT_L] as byte[], 2)
        return (short) (((b[1] & 0xFF) << 8) | (b[0] & 0xFF))
    }

    /** Read the absolute pressure in hPa. */
    double readPressure() {
        int raw = readPressureRaw()
        double sens = (fsMode == 0) ? SENSITIVITY_MODE1 : SENSITIVITY_MODE2
        return raw / sens
    }

    /** Read the sensor temperature in °C. */
    double readTemperature() {
        return readTemperatureRaw() / 100.0d
    }
}