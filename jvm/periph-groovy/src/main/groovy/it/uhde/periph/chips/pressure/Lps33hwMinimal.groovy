package it.uhde.periph.chips.pressure

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

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
@CompileStatic
class Lps33hwMinimal {

    // Register addresses
    static final int REG_INTERRUPT_CFG = 0x0B
    static final int REG_THS_P_L       = 0x0C
    static final int REG_THS_P_H       = 0x0D
    static final int REG_WHO_AM_I      = 0x0F
    static final int REG_CTRL_REG1     = 0x10
    static final int REG_CTRL_REG2     = 0x11
    static final int REG_CTRL_REG3     = 0x12
    static final int REG_FIFO_CTRL     = 0x14
    static final int REG_REF_P_XL      = 0x15
    static final int REG_REF_P_L       = 0x16
    static final int REG_REF_P_H       = 0x17
    static final int REG_RPDS_L        = 0x18
    static final int REG_RPDS_H        = 0x19
    static final int REG_RES_CONF      = 0x1A
    static final int REG_INT_SOURCE    = 0x25
    static final int REG_FIFO_STATUS   = 0x26
    static final int REG_STATUS        = 0x27
    static final int REG_PRESS_XL      = 0x28
    static final int REG_PRESS_L       = 0x29
    static final int REG_PRESS_H       = 0x2A
    static final int REG_TEMP_L        = 0x2B
    static final int REG_TEMP_H        = 0x2C
    static final int REG_LPFP_RES      = 0x33

    /** Expected chip ID. */
    static final int CHIP_ID = 0xB1

    /** Status register bits. */
    static final int STATUS_P_DA = 0x01
    static final int STATUS_T_DA = 0x02

    /** Default CTRL_REG1 (ODR=1 Hz, BDU=1). */
    static final int CTRL_REG1_DEFAULT = 0x12
    /** CTRL_REG2 with SWRESET=1. */
    static final int CTRL_REG2_RESET   = 0x04
    /** Default CTRL_REG2 after reset (IF_ADD_INC=1). */
    static final int CTRL_REG2_DEFAULT = 0x10

    protected final Connection connection

    /**
     * Construct the driver, verify the chip ID, software-reset, and apply
     * the default configuration (ODR=1 Hz, BDU=1).
     *
     * @param connection I²C connection bound to address 0x5C
     * @throws IOException on I²C error or wrong chip ID
     */
    Lps33hwMinimal(Connection connection) {
        this.connection = connection

        byte[] id = connection.writeRead([(byte) REG_WHO_AM_I] as byte[], 1)
        int chipId = id[0] & 0xFF
        if (chipId != CHIP_ID) {
            throw new IOException(
                "LPS33HW not found: expected 0xB1, got 0x" +
                Integer.toHexString(chipId))
        }

        writeReg(REG_CTRL_REG2, CTRL_REG2_RESET)
        Thread.sleep(1)
        writeReg(REG_CTRL_REG2, CTRL_REG2_DEFAULT)
        writeReg(REG_CTRL_REG1, CTRL_REG1_DEFAULT)
    }

    /**
     * Write a single byte to a register.
     *
     * @param reg   register address
     * @param value byte value to write
     * @throws IOException on I²C error
     */
    protected void writeReg(int reg, int value) {
        connection.write([(byte) reg, (byte) value] as byte[])
    }

    /**
     * Read a single byte from a register.
     *
     * @param reg register address
     * @return raw byte
     * @throws IOException on I²C error
     */
    protected int readReg(int reg) {
        byte[] b = connection.writeRead([(byte) reg] as byte[], 1)
        return b[0] & 0xFF
    }

    /**
     * Wait for the given STATUS bits to be set.
     */
    protected void waitStatus(int mask) {
        for (int i = 0; i < 50; i++) {
            int status = readReg(REG_STATUS)
            if ((status & mask) == mask) return
            Thread.sleep(5)
        }
    }

    /**
     * Burst-read PRESS_OUT_XL..TEMP_OUT_H (5 bytes) and return both the
     * calibrated pressure and temperature in one tuple.
     */
    protected double[] readPressTemp() {
        waitStatus(STATUS_P_DA | STATUS_T_DA)
        byte[] raw = connection.writeRead([(byte) REG_PRESS_XL] as byte[], 5)
        int rawPress = (raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8) | ((raw[2] & 0xFF) << 16)
        if (rawPress >= 0x800000) rawPress -= 0x1000000
        int rawTemp = (raw[3] & 0xFF) | ((raw[4] & 0xFF) << 8)
        if (rawTemp >= 0x8000) rawTemp -= 0x10000
        double pressure_Pa = rawPress * 100.0 / 4096.0
        double temperature_C = rawTemp / 100.0
        return new double[]{pressure_Pa, temperature_C}
    }

    /**
     * Read the calibrated absolute pressure.
     *
     * @return pressure in Pa
     * @throws IOException on I²C error
     */
    double pressure() {
        return readPressTemp()[0]
    }

    /**
     * Read the calibrated temperature.
     *
     * @return temperature in °C
     * @throws IOException on I²C error
     */
    double temperature() {
        return readPressTemp()[1]
    }
}