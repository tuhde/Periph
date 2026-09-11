package it.uhde.periph.chips.pressure

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * BMP384 — digital barometric pressure and temperature sensor (minimal driver).
 *
 * Reads temperature and pressure via I²C using Bosch's floating-point
 * compensation algorithm. Calibration coefficients are loaded from the chip's
 * NVM during construction. The chip ID register is verified to be 0x50.
 *
 * Default settings: osr_p=×16, osr_t=×2, iir=coef 3, ODR=25 Hz, normal mode.
 */
@CompileStatic
class Bmp384Minimal {

    static final int REG_CHIP_ID   = 0x00
    static final int REG_STATUS    = 0x03
    static final int REG_DATA_0    = 0x04
    static final int REG_PWR_CTRL  = 0x1B
    static final int REG_OSR       = 0x1C
    static final int REG_ODR       = 0x1D
    static final int REG_CONFIG    = 0x1F
    static final int REG_CMD       = 0x7E
    static final int REG_CAL_START = 0x31
    static final int REG_CAL_LEN   = 21

    static final int CHIP_ID        = 0x50
    static final int SOFT_RESET_CMD = 0xB6
    static final int FIFO_FLUSH_CMD = 0xB0

    static final int MODE_SLEEP  = 0x00
    static final int MODE_FORCED = 0x01
    static final int MODE_NORMAL = 0x03

    static final int PWR_PRESS_EN = 0x01
    static final int PWR_TEMP_EN  = 0x02

    protected final Connection connection

    protected int osrP = 4
    protected int osrT = 1
    protected int iir   = 2
    protected int odr   = 0x03
    protected int mode  = MODE_NORMAL

    protected double parT1, parT2, parT3
    protected double parP1, parP2, parP3, parP4, parP5, parP6
    protected double parP7, parP8, parP9, parP10, parP11

    protected double tLin

    Bmp384Minimal(Connection conn) { this(conn, 0x76) }

    Bmp384Minimal(Connection connection, int addr) {
        this.connection = connection
        byte[] id = connection.writeRead(new byte[]{(byte) REG_CHIP_ID} as byte[], 1)
        int chipId = id[0] & 0xFF
        if (chipId != CHIP_ID) {
            throw new IOException(
                "BMP384 not found: expected 0x50, got 0x${Integer.toHexString(chipId)}"
            )
        }
        readCalibration()
        applyConfig()
    }

    protected void readCalibration() {
        byte[] cal = connection.writeRead(new byte[]{(byte) REG_CAL_START} as byte[], REG_CAL_LEN)

        int nvmT1  = readU16LE(cal, 0)
        int nvmT2  = readU16LE(cal, 2)
        int nvmT3  = readS8(cal, 4)
        int nvmP1  = readS16LE(cal, 5)
        int nvmP2  = readS16LE(cal, 7)
        int nvmP3  = readS8(cal, 9)
        int nvmP4  = readS8(cal, 10)
        int nvmP5  = readU16LE(cal, 11)
        int nvmP6  = readU16LE(cal, 13)
        int nvmP7  = readS8(cal, 15)
        int nvmP8  = readS8(cal, 16)
        int nvmP9  = readS16LE(cal, 17)
        int nvmP10 = readS8(cal, 19)
        int nvmP11 = readS8(cal, 20)

        parT1  = nvmT1  * 256.0d
        parT2  = nvmT2  / Math.pow(2.0d, 30.0d)
        parT3  = nvmT3  / Math.pow(2.0d, 48.0d)
        parP1  = (nvmP1  - Math.pow(2.0d, 14.0d)) / Math.pow(2.0d, 20.0d)
        parP2  = (nvmP2  - Math.pow(2.0d, 14.0d)) / Math.pow(2.0d, 29.0d)
        parP3  = nvmP3  / Math.pow(2.0d, 32.0d)
        parP4  = nvmP4  / Math.pow(2.0d, 37.0d)
        parP5  = nvmP5  * 8.0d
        parP6  = nvmP6  / Math.pow(2.0d, 6.0d)
        parP7  = nvmP7  / Math.pow(2.0d, 8.0d)
        parP8  = nvmP8  / Math.pow(2.0d, 15.0d)
        parP9  = nvmP9  / Math.pow(2.0d, 48.0d)
        parP10 = nvmP10 / Math.pow(2.0d, 48.0d)
        parP11 = nvmP11 / Math.pow(2.0d, 65.0d)
    }

    protected void applyConfig() {
        int osrReg = (osrT << 3) | (osrP << 0)
        int configReg = (iir << 1)
        int pwrReg = (mode << 4) | PWR_TEMP_EN | PWR_PRESS_EN
        writeReg(REG_OSR,      osrReg)
        writeReg(REG_CONFIG,   configReg)
        writeReg(REG_ODR,      odr)
        writeReg(REG_PWR_CTRL, pwrReg)
    }

    protected void writeReg(int reg, int value) {
        connection.write(new byte[]{(byte) reg, (byte) value} as byte[])
    }

    protected int readReg(int reg) {
        byte[] b = connection.writeRead(new byte[]{(byte) reg} as byte[], 1)
        return b[0] & 0xFF
    }

    protected int[] readBurst() {
        byte[] raw = connection.writeRead(new byte[]{(byte) REG_DATA_0} as byte[], 6)
        int uncompPress = ((raw[2] & 0xFF) << 16) | ((raw[1] & 0xFF) << 8) | (raw[0] & 0xFF)
        int uncompTemp  = ((raw[5] & 0xFF) << 16) | ((raw[4] & 0xFF) << 8) | (raw[3] & 0xFF)
        return new int[]{uncompPress, uncompTemp}
    }

    protected double compensateTemperature(int uncompTemp) {
        double partial1 = uncompTemp - parT1
        double partial2 = partial1 * parT2
        tLin = partial2 + (partial1 * partial1) * parT3
        return tLin
    }

    protected double compensatePressure(int uncompPress) {
        double partial1 = parP6 * tLin
        double partial2 = parP7 * tLin * tLin
        double partial3 = parP8 * tLin * tLin * tLin
        double partialOut1 = parP5 + partial1 + partial2 + partial3

        partial1 = parP2 * tLin
        partial2 = parP3 * tLin * tLin
        partial3 = parP4 * tLin * tLin * tLin
        double partialOut2 = uncompPress * (parP1 + partial1 + partial2 + partial3)

        partial1 = uncompPress * uncompPress
        partial2 = parP9 + parP10 * tLin
        partial3 = partial1 * partial2
        double partial4 = partial3 + uncompPress * uncompPress * uncompPress * parP11

        return partialOut1 + partialOut2 + partial4
    }

    double temperature() {
        if (mode == MODE_FORCED) {
            writeReg(REG_PWR_CTRL, (MODE_FORCED << 4) | PWR_TEMP_EN | PWR_PRESS_EN)
            try { Thread.sleep(40) } catch (InterruptedException e) { Thread.currentThread().interrupt() }
        }
        int[] burst = readBurst()
        return compensateTemperature(burst[1])
    }

    double pressure() {
        if (mode == MODE_FORCED) {
            writeReg(REG_PWR_CTRL, (MODE_FORCED << 4) | PWR_TEMP_EN | PWR_PRESS_EN)
            try { Thread.sleep(40) } catch (InterruptedException e) { Thread.currentThread().interrupt() }
        }
        int[] burst = readBurst()
        compensateTemperature(burst[1])
        return compensatePressure(burst[0]) / 100.0d
    }

    private static int readU16LE(byte[] buf, int off) {
        return (buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8)
    }

    private static int readS16LE(byte[] buf, int off) {
        int v = (buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8)
        return v >= 0x8000 ? v - 0x10000 : v
    }

    private static int readS8(byte[] buf, int off) {
        int v = buf[off] & 0xFF
        return v >= 0x80 ? v - 0x100 : v
    }
}
