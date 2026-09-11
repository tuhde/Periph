package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * LPS22DF absolute pressure and temperature sensor (STMicroelectronics).
 *
 * <p>Provides pressure (Pa) and temperature (°C) readings via I²C, with no
 * configuration beyond the connection. I²C address is 0x5C (SA0=GND) or
 * 0x5D (SA0=VDDIO). The chip has built-in factory calibration; no user
 * calibration read step is required.
 *
 * <p>Pressure is 24-bit two's complement at 4096 LSB/hPa; temperature is
 * 16-bit two's complement at 100 LSB/°C.
 *
 * <p>Default settings: ODR=10 Hz, AVG=4 samples, BDU on, low-pass filter off,
 * FIFO bypass mode.
 */
public class Lps22dfMinimal {

    /** Bus type: I²C (default) — register addresses used unmasked for both reads and writes. */
    public static final int BUS_I2C = 0;
    /** Bus type: SPI — write addresses have bit 7 cleared, read addresses have bit 7 set. */
    public static final int BUS_SPI = 1;

    // Register addresses
    protected static final int REG_INTERRUPT_CFG = 0x0B;
    protected static final int REG_THS_P_L       = 0x0C;
    protected static final int REG_THS_P_H       = 0x0D;
    protected static final int REG_IF_CTRL       = 0x0E;
    protected static final int REG_WHO_AM_I      = 0x0F;
    protected static final int REG_CTRL_REG1     = 0x10;
    protected static final int REG_CTRL_REG2     = 0x11;
    protected static final int REG_CTRL_REG3     = 0x12;
    protected static final int REG_CTRL_REG4     = 0x13;
    protected static final int REG_FIFO_CTRL     = 0x14;
    protected static final int REG_FIFO_WTM      = 0x15;
    protected static final int REG_REF_P_L       = 0x16;
    protected static final int REG_REF_P_H       = 0x17;
    protected static final int REG_RPDS_L        = 0x1A;
    protected static final int REG_RPDS_H        = 0x1B;
    protected static final int REG_INT_SOURCE    = 0x24;
    protected static final int REG_FIFO_STATUS1  = 0x25;
    protected static final int REG_STATUS        = 0x27;
    protected static final int REG_PRESS_OUT_XL  = 0x28;
    protected static final int REG_TEMP_OUT_L     = 0x2B;
    protected static final int REG_FIFO_PRESS_XL  = 0x78;

    /** Expected chip ID for the LPS22DF. */
    protected static final int CHIP_ID = 0xB4;

    /** Status flag: pressure data available. */
    protected static final int STATUS_P_DA = 0x01;

    protected final Connection connection;
    protected final int busType;
    protected final int addr;

    /**
     * Construct at the default address (0x5C).
     *
     * @param connection I²C connection bound to address 0x5C.
     * @throws IOException on I²C error or wrong chip ID.
     */
    public Lps22dfMinimal(Connection connection) throws IOException {
        this(connection, 0x5C, BUS_I2C);
    }

    /**
     * Construct at the given address.
     *
     * @param connection I²C connection bound to the chip.
     * @param addr       I²C device address (0x5C or 0x5D).
     * @throws IOException on I²C error or wrong chip ID.
     */
    public Lps22dfMinimal(Connection connection, int addr) throws IOException {
        this(connection, addr, BUS_I2C);
    }

    /**
     * Construct at the given address and bus type.
     *
     * @param connection I²C or SPI connection.
     * @param addr       I²C device address; unused for SPI.
     * @param busType    {@link #BUS_I2C} or {@link #BUS_SPI}.
     * @throws IOException on bus error or wrong chip ID.
     */
    public Lps22dfMinimal(Connection connection, int addr, int busType) throws IOException {
        this.connection = connection;
        this.addr = addr;
        this.busType = busType;

        byte[] who = readReg(REG_WHO_AM_I, 1);
        if ((who[0] & 0xFF) != CHIP_ID) {
            throw new IOException(
                    "LPS22DF not found: expected WHO_AM_I 0x" + Integer.toHexString(CHIP_ID)
                    + ", got 0x" + Integer.toHexString(who[0] & 0xFF));
        }

        writeReg(REG_CTRL_REG2, 0x04);  // SWRESET=1
        try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        // CTRL_REG1: ODR[3:0]=0011 (10 Hz), AVG[2:0]=000 (4 samples)
        writeReg(REG_CTRL_REG1, (3 << 3) | 0);
        // CTRL_REG2: BDU=1
        writeReg(REG_CTRL_REG2, 0x08);
    }

    /**
     * Write a single byte to a register, applying the SPI write-address mask
     * when this driver was constructed with {@link #BUS_SPI}.
     *
     * @param reg   register address (I²C / unmasked form)
     * @param value byte value to write
     * @throws IOException on bus error
     */
    protected void writeReg(int reg, int value) throws IOException {
        int a = (busType == BUS_SPI) ? (reg & 0x7F) : reg;
        connection.write(new byte[]{(byte) a, (byte) value});
    }

    /**
     * Read bytes from a register, setting the SPI read-address bit when
     * this driver was constructed with {@link #BUS_SPI}.
     *
     * @param reg register address
     * @param len number of bytes to read
     * @return the bytes read
     * @throws IOException on bus error
     */
    protected byte[] readReg(int reg, int len) throws IOException {
        int a = (busType == BUS_SPI) ? (reg | 0x80) : reg;
        return connection.writeRead(new byte[]{(byte) a}, len);
    }

    /** Poll STATUS until P_DA is set. */
    protected void waitPDa() throws IOException {
        while (true) {
            byte[] status = readReg(REG_STATUS, 1);
            if ((status[0] & 0xFF) != 0 && (status[0] & STATUS_P_DA) != 0) return;
            try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /**
     * Read absolute pressure.
     *
     * <p>Polls STATUS.P_DA then burst-reads PRESS_OUT_XL..H. Sign-extends the
     * 24-bit two's complement value and converts to pascals (4096 LSB/hPa).
     *
     * @return pressure in pascals
     * @throws IOException on I²C error
     */
    public double pressure() throws IOException {
        waitPDa();
        byte[] raw = readReg(REG_PRESS_OUT_XL, 3);
        int value = (raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8) | ((raw[2] & 0xFF) << 16);
        if ((value & 0x800000) != 0) value -= 0x1000000;
        return (value / 4096.0) * 100.0;
    }

    /**
     * Read temperature.
     *
     * <p>Reads TEMP_OUT_L..H. Sign-extends the 16-bit two's complement value
     * and converts to °C (100 LSB/°C).
     *
     * @return temperature in degrees Celsius
     * @throws IOException on I²C error
     */
    public double temperature() throws IOException {
        byte[] raw = readReg(REG_TEMP_OUT_L, 2);
        short s = (short)(((raw[0] & 0xFF) << 0) | ((raw[1] & 0xFF) << 8));
        return s / 100.0;
    }

    /** @return chip ID register value (expected 0xB4). */
    public int whoAmI() throws IOException {
        byte[] v = readReg(REG_WHO_AM_I, 1);
        return v[0] & 0xFF;
    }
}