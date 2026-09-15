package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * LPS28DFW — dual full-scale digital barometer (minimal driver).
 *
 * <p>Reads absolute pressure and temperature from the STMicroelectronics
 * CCLGA-7L water-resistant sensor. I²C address is 0x5C (SA0=GND) or
 * 0x5D (SA0=VDD). Pressure is 24-bit signed, temperature is 16-bit signed.
 *
 * <p>Default configuration (baked in at construction):
 * <ul>
 *   <li>FS_MODE = 0 (Mode 1, 0–1260 hPa, 4096 LSB/hPa)</li>
 *   <li>AVG = 0b010 (16 samples)</li>
 *   <li>ODR = 0b0100 (25 Hz)</li>
 *   <li>BDU = 1, EN_LPFP = 1, LFPF_CFG = 0 (ODR/4 bandwidth)</li>
 * </ul>
 *
 * <p>Factory calibration is applied in hardware; the 24-bit pressure output
 * is already compensated — just divide by 4096 (or 2048 in Mode 2) to get hPa.
 */
public class Lps28dfwMinimal {

    /** WHO_AM_I value: fixed device identifier for LPS28DFW. */
    public static final int CHIP_ID = 0xB4;

    /** Full-scale mode 1: 0–1260 hPa, 4096 LSB/hPa. */
    public static final int FS_MODE_1 = 0;
    /** Full-scale mode 2: 0–4060 hPa, 2048 LSB/hPa. */
    public static final int FS_MODE_2 = 1;

    protected static final int REG_INTERRUPT_CFG = 0x0B;
    protected static final int REG_WHO_AM_I      = 0x0F;
    protected static final int REG_CTRL_REG1     = 0x10;
    protected static final int REG_CTRL_REG2     = 0x11;
    protected static final int REG_STATUS        = 0x27;
    protected static final int REG_PRESS_OUT_XL  = 0x28;
    protected static final int REG_PRESS_OUT_L   = 0x29;
    protected static final int REG_PRESS_OUT_H   = 0x2A;
    protected static final int REG_TEMP_OUT_L    = 0x2B;
    protected static final int REG_TEMP_OUT_H    = 0x2C;

    protected static final double SENSITIVITY_MODE1 = 4096.0;
    protected static final double SENSITIVITY_MODE2 = 2048.0;

    protected final Connection connection;
    protected int fsMode = 0;
    protected int odr = 0x04;
    protected int avg = 0x02;
    protected int lpfEn = 1;
    protected int lpfCfg = 0;
    protected int bdu = 1;

    /**
     * Construct the driver at the default address (0x5C), verify WHO_AM_I,
     * and apply default configuration.
     *
     * @param connection I²C connection bound to address 0x5C
     * @throws IOException on I²C error or WHO_AM_I mismatch
     */
    public Lps28dfwMinimal(Connection connection) throws IOException {
        this.connection = connection;
        init();
    }

    /**
     * Apply the default configuration (FS_MODE=0, AVG=16, ODR=25 Hz, BDU=1,
     * EN_LPFP=1, LFPF_CFG=0).
     *
     * @throws IOException on I²C error
     */
    protected void init() throws IOException {
        byte[] id = connection.writeRead(new byte[]{REG_WHO_AM_I}, 1);
        if ((id[0] & 0xFF) != CHIP_ID) {
            throw new IOException("LPS28DFW WHO_AM_I mismatch: expected 0x" +
                    Integer.toHexString(CHIP_ID) + ", got 0x" +
                    Integer.toHexString(id[0] & 0xFF));
        }
        try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        int ctrl2 = (fsMode << 6) | (lpfCfg << 5) | (lpfEn << 4) | (bdu << 3);
        writeReg(REG_CTRL_REG2, ctrl2);
        int ctrl1 = (odr << 3) | (avg & 0x07);
        writeReg(REG_CTRL_REG1, ctrl1);
    }

    /**
     * Write one byte to a register.
     *
     * @param reg register address
     * @param value value to write
     * @throws IOException on I²C error
     */
    protected void writeReg(int reg, int value) throws IOException {
        connection.write(new byte[]{(byte) reg, (byte) value});
    }

    /**
     * Read raw 24-bit pressure value (signed).
     *
     * @return raw pressure as a sign-extended 24-bit integer
     * @throws IOException on I²C error
     */
    protected int readPressureRaw() throws IOException {
        byte[] b = connection.writeRead(new byte[]{REG_PRESS_OUT_XL}, 3);
        int v = ((b[2] & 0xFF) << 16) | ((b[1] & 0xFF) << 8) | (b[0] & 0xFF);
        if ((v & 0x800000) != 0) v |= 0xFF000000;
        return v;
    }

    /**
     * Read raw 16-bit temperature value (signed).
     *
     * @return raw temperature as a signed 16-bit integer
     * @throws IOException on I²C error
     */
    protected int readTemperatureRaw() throws IOException {
        byte[] b = connection.writeRead(new byte[]{REG_TEMP_OUT_L}, 2);
        return (short) (((b[1] & 0xFF) << 8) | (b[0] & 0xFF));
    }

    /**
     * Read the absolute pressure.
     *
     * @return pressure in hPa
     * @throws IOException on I²C error
     */
    public double readPressure() throws IOException {
        int raw = readPressureRaw();
        double sens = (fsMode == 0) ? SENSITIVITY_MODE1 : SENSITIVITY_MODE2;
        return raw / sens;
    }

    /**
     * Read the sensor temperature.
     *
     * @return temperature in °C
     * @throws IOException on I²C error
     */
    public double readTemperature() throws IOException {
        int raw = readTemperatureRaw();
        return raw / 100.0;
    }
}