package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * BMP384 — digital barometric pressure and temperature sensor (minimal driver).
 *
 * <p>Reads temperature and pressure via I²C using Bosch's floating-point
 * compensation algorithm. Calibration coefficients are loaded from the chip's
 * NVM during construction. The chip ID register is verified to be 0x50.
 *
 * <p>Configurable I²C address: 0x76 (SDO low, default) or 0x77 (SDO high).
 *
 * <p>Default settings: osr_p=×16, osr_t=×2, iir=coef 3, ODR=25 Hz,
 * normal mode, both sensors enabled.
 */
public class Bmp384Minimal {

    // Register addresses
    protected static final int REG_CHIP_ID   = 0x00;
    protected static final int REG_STATUS    = 0x03;
    protected static final int REG_DATA_0    = 0x04;
    protected static final int REG_PWR_CTRL  = 0x1B;
    protected static final int REG_OSR       = 0x1C;
    protected static final int REG_ODR       = 0x1D;
    protected static final int REG_CONFIG    = 0x1F;
    protected static final int REG_CMD       = 0x7E;
    protected static final int REG_CAL_START = 0x31;
    protected static final int REG_CAL_LEN   = 21;

    protected static final int CHIP_ID        = 0x50;
    protected static final int SOFT_RESET_CMD = 0xB6;
    protected static final int FIFO_FLUSH_CMD = 0xB0;

    protected static final int MODE_SLEEP     = 0x00;
    protected static final int MODE_FORCED    = 0x01;
    protected static final int MODE_NORMAL    = 0x03;

    protected static final int PWR_PRESS_EN   = 0x01;
    protected static final int PWR_TEMP_EN    = 0x02;

    protected final Connection connection;

    /** PAR coefficients — scaled to floating-point per datasheet. */
    protected double parT1, parT2, parT3;
    protected double parP1, parP2, parP3, parP4, parP5, parP6;
    protected double parP7, parP8, parP9, parP10, parP11;

    /** Compensated temperature cached for use by the pressure compensation. */
    protected double tLin;

    /** Pressure oversampling index (0–5). */
    protected int osrP = 4;
    /** Temperature oversampling index (0–5). */
    protected int osrT = 1;
    /** IIR filter coefficient index (0–7). */
    protected int iir = 2;
    /** Output data rate selector (0x00–0x11). */
    protected int odr = 0x03;
    /** Power-mode bits in PWR_CTRL. */
    protected int mode = MODE_NORMAL;

    /**
     * Construct the driver at the default address (0x76), verify the chip ID,
     * and load calibration data.
     *
     * @param connection I²C connection bound to address 0x76
     * @throws IOException on I²C error, wrong chip ID, or invalid calibration
     */
    public Bmp384Minimal(Connection connection) throws IOException {
        this(connection, 0x76);
    }

    /**
     * Construct the driver at the given address, verify the chip ID, and load
     * calibration data.
     *
     * @param connection I²C connection bound to the given address
     * @param addr      I²C device address (0x76 or 0x77)
     * @throws IOException on I²C error, wrong chip ID, or invalid calibration
     */
    public Bmp384Minimal(Connection connection, int addr) throws IOException {
        this.connection = connection;

        byte[] id = connection.writeRead(new byte[]{(byte) REG_CHIP_ID}, 1);
        int chipId = id[0] & 0xFF;
        if (chipId != CHIP_ID) {
            throw new IOException(
                    "BMP384 not found: expected 0x50, got 0x"
                    + Integer.toHexString(chipId));
        }
        readCalibration();
        applyConfig();
    }

    /**
     * Read and unpack the 21-byte calibration block from NVM (0x31–0x45).
     *
     * <p>All multi-byte NVM values are little-endian. PAR_T1 and PAR_T5/PAR_T6
     * are unsigned 16-bit; PAR_P1/PAR_P2/PAR_P9 are signed 16-bit; PAR_T3,
     * PAR_P3, PAR_P4, PAR_P7, PAR_P8, PAR_P10, PAR_P11 are signed 8-bit.
     *
     * @throws IOException on I²C error
     */
    protected void readCalibration() throws IOException {
        byte[] cal = connection.writeRead(new byte[]{(byte) REG_CAL_START}, REG_CAL_LEN);

        int nvmT1  = readU16LE(cal, 0);
        int nvmT2  = readU16LE(cal, 2);
        int nvmT3  = readS8(cal, 4);
        int nvmP1  = readS16LE(cal, 5);
        int nvmP2  = readS16LE(cal, 7);
        int nvmP3  = readS8(cal, 9);
        int nvmP4  = readS8(cal, 10);
        int nvmP5  = readU16LE(cal, 11);
        int nvmP6  = readU16LE(cal, 13);
        int nvmP7  = readS8(cal, 15);
        int nvmP8  = readS8(cal, 16);
        int nvmP9  = readS16LE(cal, 17);
        int nvmP10 = readS8(cal, 19);
        int nvmP11 = readS8(cal, 20);

        // Convert raw NVM coefficients to floating-point PAR values per datasheet.
        parT1  = nvmT1  * 256.0;                                  // × 2^8
        parT2  = nvmT2  / Math.pow(2.0, 30);
        parT3  = nvmT3  / Math.pow(2.0, 48);
        parP1  = (nvmP1  - Math.pow(2.0, 14)) / Math.pow(2.0, 20);
        parP2  = (nvmP2  - Math.pow(2.0, 14)) / Math.pow(2.0, 29);
        parP3  = nvmP3  / Math.pow(2.0, 32);
        parP4  = nvmP4  / Math.pow(2.0, 37);
        parP5  = nvmP5  * 8.0;                                    // × 2^3
        parP6  = nvmP6  / Math.pow(2.0, 6);
        parP7  = nvmP7  / Math.pow(2.0, 8);
        parP8  = nvmP8  / Math.pow(2.0, 15);
        parP9  = nvmP9  / Math.pow(2.0, 48);
        parP10 = nvmP10 / Math.pow(2.0, 48);
        parP11 = nvmP11 / Math.pow(2.0, 65);
    }

    /** Write OSR, CONFIG, ODR, and PWR_CTRL with the currently-cached settings. */
    protected void applyConfig() throws IOException {
        int osrReg = (osrT << 3) | (osrP << 0);
        int configReg = (iir << 1);
        int pwrReg = (mode << 4) | PWR_TEMP_EN | PWR_PRESS_EN;
        writeReg(REG_OSR,      osrReg);
        writeReg(REG_CONFIG,   configReg);
        writeReg(REG_ODR,      odr);
        writeReg(REG_PWR_CTRL, pwrReg);
    }

    /**
     * Write a single byte to a register.
     *
     * @param reg   register address
     * @param value byte value
     */
    protected void writeReg(int reg, int value) throws IOException {
        connection.write(new byte[]{(byte) reg, (byte) value});
    }

    /**
     * Read a single byte from a register.
     */
    protected int readReg(int reg) throws IOException {
        byte[] b = connection.writeRead(new byte[]{(byte) reg}, 1);
        return b[0] & 0xFF;
    }

    /**
     * Burst-read 6 bytes from DATA_0..DATA_5, returning (uncomp_press, uncomp_temp).
     */
    protected int[] readBurst() throws IOException {
        byte[] raw = connection.writeRead(new byte[]{(byte) REG_DATA_0}, 6);
        int uncompPress = ((raw[2] & 0xFF) << 16) | ((raw[1] & 0xFF) << 8) | (raw[0] & 0xFF);
        int uncompTemp  = ((raw[5] & 0xFF) << 16) | ((raw[4] & 0xFF) << 8) | (raw[3] & 0xFF);
        return new int[]{uncompPress, uncompTemp};
    }

    /**
     * Compute temperature compensation and update tLin.
     *
     * @param uncompTemp raw 24-bit temperature ADC value
     * @return temperature in °C
     */
    protected double compensateTemperature(int uncompTemp) {
        double partial1 = uncompTemp - parT1;
        double partial2 = partial1 * parT2;
        tLin = partial2 + (partial1 * partial1) * parT3;
        return tLin;
    }

    /**
     * Compute pressure compensation using the current tLin value.
     *
     * @param uncompPress raw 24-bit pressure ADC value
     * @return pressure in Pa
     */
    protected double compensatePressure(int uncompPress) {
        double partial1 = parP6 * tLin;
        double partial2 = parP7 * tLin * tLin;
        double partial3 = parP8 * tLin * tLin * tLin;
        double partialOut1 = parP5 + partial1 + partial2 + partial3;

        partial1 = parP2 * tLin;
        partial2 = parP3 * tLin * tLin;
        partial3 = parP4 * tLin * tLin * tLin;
        double partialOut2 = uncompPress * (parP1 + partial1 + partial2 + partial3);

        partial1 = uncompPress * uncompPress;
        partial2 = parP9 + parP10 * tLin;
        partial3 = partial1 * partial2;
        double partial4 = partial3 + uncompPress * uncompPress * uncompPress * parP11;

        return partialOut1 + partialOut2 + partial4;
    }

    /**
     * Read the temperature.
     *
     * @return temperature in °C
     * @throws IOException on I²C error
     */
    public double temperature() throws IOException {
        if (mode == MODE_FORCED) {
            writeReg(REG_PWR_CTRL, (MODE_FORCED << 4) | PWR_TEMP_EN | PWR_PRESS_EN);
            try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        int[] burst = readBurst();
        return compensateTemperature(burst[1]);
    }

    /**
     * Read the pressure.
     *
     * @return pressure in hPa
     * @throws IOException on I²C error
     */
    public double pressure() throws IOException {
        if (mode == MODE_FORCED) {
            writeReg(REG_PWR_CTRL, (MODE_FORCED << 4) | PWR_TEMP_EN | PWR_PRESS_EN);
            try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        int[] burst = readBurst();
        compensateTemperature(burst[1]);   // populate tLin
        return compensatePressure(burst[0]) / 100.0;
    }

    private static int readU16LE(byte[] buf, int off) {
        return (buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8);
    }

    private static int readS16LE(byte[] buf, int off) {
        int v = (buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8);
        return v >= 0x8000 ? v - 0x10000 : v;
    }

    private static int readS8(byte[] buf, int off) {
        int v = buf[off] & 0xFF;
        return v >= 0x80 ? v - 0x100 : v;
    }
}
