package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.Connection

import groovy.transform.CompileStatic

/**
 * LPS22DF absolute pressure and temperature sensor (STMicroelectronics).
 *
 * Provides pressure (Pa) and temperature (°C) readings via I²C with no
 * configuration beyond the connection. I²C address is 0x5C (SA0=GND) or
 * 0x5D (SA0=VDDIO). The chip has built-in factory calibration; no user
 * calibration read step is required.
 *
 * Pressure is 24-bit two's complement at 4096 LSB/hPa; temperature is
 * 16-bit two's complement at 100 LSB/°C.
 *
 * Default settings: ODR=10 Hz, AVG=4 samples, BDU on, low-pass filter off,
 * FIFO bypass mode.
 */
@CompileStatic
class Lps22dfMinimal {

    public static final int BUS_I2C = 0
    public static final int BUS_SPI = 1

    protected static final int REG_INTERRUPT_CFG = 0x0B
    protected static final int REG_THS_P_L       = 0x0C
    protected static final int REG_THS_P_H       = 0x0D
    protected static final int REG_WHO_AM_I      = 0x0F
    protected static final int REG_CTRL_REG1     = 0x10
    protected static final int REG_CTRL_REG2     = 0x11
    protected static final int REG_CTRL_REG3     = 0x12
    protected static final int REG_CTRL_REG4     = 0x13
    protected static final int REG_FIFO_CTRL     = 0x14
    protected static final int REG_FIFO_WTM      = 0x15
    protected static final int REG_REF_P_L       = 0x16
    protected static final int REG_REF_P_H       = 0x17
    protected static final int REG_RPDS_L        = 0x1A
    protected static final int REG_RPDS_H        = 0x1B
    protected static final int REG_INT_SOURCE    = 0x24
    protected static final int REG_FIFO_STATUS1  = 0x25
    protected static final int REG_STATUS        = 0x27
    protected static final int REG_PRESS_OUT_XL  = 0x28
    protected static final int REG_TEMP_OUT_L    = 0x2B
    protected static final int REG_FIFO_PRESS_XL = 0x78

    protected static final int CHIP_ID     = 0xB4
    protected static final int STATUS_P_DA = 0x01

    protected final Connection connection
    protected final int addr
    protected final int busType

    Lps22dfMinimal(Connection connection) {
        this(connection, 0x5C, BUS_I2C)
    }

    Lps22dfMinimal(Connection connection, int addr) {
        this(connection, addr, BUS_I2C)
    }

    Lps22dfMinimal(Connection connection, int addr, int busType) {
        this.connection = connection
        this.addr = addr
        this.busType = busType

        byte[] who = readReg(REG_WHO_AM_I, 1)
        if ((who[0] & 0xFF) != CHIP_ID) {
            throw new IOException(
                "LPS22DF not found: expected WHO_AM_I 0x${Integer.toHexString(CHIP_ID)}, got 0x${Integer.toHexString(who[0] & 0xFF)}"
            )
        }
        writeReg(REG_CTRL_REG2, 0x04)
        Thread.sleep(1)
        writeReg(REG_CTRL_REG1, (3 << 3) | 0)
        writeReg(REG_CTRL_REG2, 0x08)
    }

    protected void writeReg(int reg, int value) {
        int a = (busType == BUS_SPI) ? (reg & 0x7F) : reg
        connection.write(new byte[]{(byte) a, (byte) value})
    }

    protected byte[] readReg(int reg, int len) {
        int a = (busType == BUS_SPI) ? (reg | 0x80) : reg
        return connection.writeRead(new byte[]{(byte) a}, len)
    }

    protected void waitPDa() {
        while (true) {
            byte[] status = readReg(REG_STATUS, 1)
            if ((status[0] & 0xFF) != 0 && (status[0] & STATUS_P_DA) != 0) return
            Thread.sleep(1)
        }
    }

    double pressure() {
        waitPDa()
        byte[] raw = readReg(REG_PRESS_OUT_XL, 3)
        int value = (raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8) | ((raw[2] & 0xFF) << 16)
        if ((value & 0x800000) != 0) value -= 0x1000000
        return (value / 4096.0d) * 100.0d
    }

    double temperature() {
        byte[] raw = readReg(REG_TEMP_OUT_L, 2)
        short s = (short)(((raw[0] & 0xFF) << 0) | ((raw[1] & 0xFF) << 8))
        return s / 100.0d
    }

    int whoAmI() {
        byte[] v = readReg(REG_WHO_AM_I, 1)
        return v[0] & 0xFF
    }
}