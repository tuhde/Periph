package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * LPS33HW — water-resistant MEMS absolute pressure sensor (minimal driver).
 *
 * <p>Reads pressure in pascals and temperature in degrees Celsius via I²C.
 * The chip ID register is verified to be {@code 0xB1}. With BDU=1 the output
 * register latch only releases after {@code PRESS_OUT_H} (0x2A) has been
 * read, so the driver always reads a 5-byte burst from PRESS_OUT_XL through
 * TEMP_OUT_H to release the latch correctly.
 *
 * <p>Configurable I²C address: {@code 0x5C} (SA0=GND, default) or
 * {@code 0x5D} (SA0=VDD).
 *
 * <p>Default settings: ODR=1 Hz (CTRL_REG1=0x12), BDU=1, EN_LPFP=0,
 * IF_ADD_INC=1.
 */
public class Lps33hwMinimal {

    // Register addresses
    protected static final int REG_INTERRUPT_CFG = 0x0B;
    protected static final int REG_THS_P_L       = 0x0C;
    protected static final int REG_THS_P_H       = 0x0D;
    protected static final int REG_WHO_AM_I      = 0x0F;
    protected static final int REG_CTRL_REG1     = 0x10;
    protected static final int REG_CTRL_REG2     = 0x11;
    protected static final int REG_CTRL_REG3     = 0x12;
    protected static final int REG_FIFO_CTRL     = 0x14;
    protected static final int REG_REF_P_XL      = 0x15;
    protected static final int REG_REF_P_L       = 0x16;
    protected static final int REG_REF_P_H       = 0x17;
    protected static final int REG_RPDS_L        = 0x18;
    protected static final int REG_RPDS_H        = 0x19;
    protected static final int REG_RES_CONF      = 0x1A;
    protected static final int REG_INT_SOURCE    = 0x25;
    protected static final int REG_FIFO_STATUS   = 0x26;
    protected static final int REG_STATUS        = 0x27;
    protected static final int REG_PRESS_XL      = 0x28;
    protected static final int REG_PRESS_L       = 0x29;
    protected static final int REG_PRESS_H       = 0x2A;
    protected static final int REG_TEMP_L        = 0x2B;
    protected static final int REG_TEMP_H        = 0x2C;
    protected static final int REG_LPFP_RES      = 0x33;

    /** Expected chip ID. */
    protected static final int CHIP_ID = 0xB1;

    /** Status register bits. */
    protected static final int STATUS_P_DA = 0x01;
    protected static final int STATUS_T_DA = 0x02;

    /** Default CTRL_REG1 (ODR=1 Hz, BDU=1). */
    protected static final int CTRL_REG1_DEFAULT = 0x12;
    /** CTRL_REG2 with SWRESET=1. */
    protected static final int CTRL_REG2_RESET   = 0x04;
    /** Default CTRL_REG2 after reset (IF_ADD_INC=1). */
    protected static final int CTRL_REG2_DEFAULT = 0x10;

    protected final Connection connection;

    /**
     * Construct the driver, verify the chip ID, software-reset, and apply
     * the default configuration (ODR=1 Hz, BDU=1).
     *
     * @param connection I²C connection bound to address 0x5C
     * @throws IOException on I²C error or wrong chip ID
     */
    public Lps33hwMinimal(Connection connection) throws IOException {
        this.connection = connection;

        // Verify chip ID.
        byte[] id = connection.writeRead(new byte[]{(byte) REG_WHO_AM_I}, 1);
        int chipId = id[0] & 0xFF;
        if (chipId != CHIP_ID) {
            throw new IOException(
                    "LPS33HW not found: expected 0xB1, got 0x"
                    + Integer.toHexString(chipId));
        }

        // Software reset, then restore IF_ADD_INC=1, then default CTRL_REG1.
        writeReg(REG_CTRL_REG2, CTRL_REG2_RESET);
        try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        writeReg(REG_CTRL_REG2, CTRL_REG2_DEFAULT);
        writeReg(REG_CTRL_REG1, CTRL_REG1_DEFAULT);
    }

    /**
     * Write a single byte to a register.
     *
     * @param reg   register address
     * @param value byte value to write
     * @throws IOException on I²C error
     */
    protected void writeReg(int reg, int value) throws IOException {
        connection.write(new byte[]{(byte) reg, (byte) value});
    }

    /**
     * Read a single byte from a register.
     *
     * @param reg register address
     * @return raw byte
     * @throws IOException on I²C error
     */
    protected int readReg(int reg) throws IOException {
        byte[] b = connection.writeRead(new byte[]{(byte) reg}, 1);
        return b[0] & 0xFF;
    }

    /**
     * Wait for the given STATUS bits to be set.
     *
     * @param mask bitmask to wait for
     * @throws IOException on I²C error
     */
    protected void waitStatus(int mask) throws IOException {
        for (int i = 0; i < 50; i++) {
            int status = readReg(REG_STATUS);
            if ((status & mask) == mask) {
                return;
            }
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /**
     * Burst-read PRESS_OUT_XL..TEMP_OUT_H (5 bytes) and return both the
     * calibrated pressure and temperature in one tuple.
     *
     * <p>With BDU=1 the latch releases only after PRESS_OUT_H has been read,
     * which falls inside the 5-byte burst.
     *
     * @return {@code [pressure_Pa, temperature_C]}
     * @throws IOException on I²C error
     */
    protected double[] readPressTemp() throws IOException {
        waitStatus(STATUS_P_DA | STATUS_T_DA);
        byte[] raw = connection.writeRead(new byte[]{(byte) REG_PRESS_XL}, 5);
        int rawPress = ((raw[0] & 0xFF))
                     | ((raw[1] & 0xFF) << 8)
                     | ((raw[2] & 0xFF) << 16);
        if (rawPress >= 0x800000) rawPress -= 0x1000000;
        int rawTemp = (raw[3] & 0xFF) | ((raw[4] & 0xFF) << 8);
        if (rawTemp >= 0x8000) rawTemp -= 0x10000;
        double pressure_Pa = rawPress * 100.0 / 4096.0;
        double temperature_C = rawTemp / 100.0;
        return new double[]{pressure_Pa, temperature_C};
    }

    /**
     * Read the calibrated absolute pressure.
     *
     * @return pressure in Pa
     * @throws IOException on I²C error
     */
    public double pressure() throws IOException {
        return readPressTemp()[0];
    }

    /**
     * Read the calibrated temperature.
     *
     * @return temperature in °C
     * @throws IOException on I²C error
     */
    public double temperature() throws IOException {
        return readPressTemp()[1];
    }
}