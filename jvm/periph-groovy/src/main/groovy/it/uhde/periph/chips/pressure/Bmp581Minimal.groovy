package it.uhde.periph.chips.pressure

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * BMP581 — digital barometric pressure and temperature sensor (minimal driver).
 *
 * The BMP581 performs factory calibration internally and outputs
 * already-compensated Pa and °C values, so unlike the BMP280 this driver
 * has no calibration NVM to read and no compensation math — just burst
 * reads of the data registers and fixed-point decode.
 */
@CompileStatic
class Bmp581Minimal {

    static final int BUS_I2C = 0
    static final int BUS_SPI = 1

    protected static final int REG_CHIP_ID    = 0x01
    protected static final int REG_STATUS     = 0x28
    protected static final int REG_INT_STATUS = 0x27
    protected static final int REG_TEMP_XLSB  = 0x1D
    protected static final int REG_PRESS_XLSB = 0x20
    protected static final int REG_OSR_CONFIG = 0x36
    protected static final int REG_ODR_CONFIG = 0x37
    protected static final int REG_CMD        = 0x7E

    protected static final int CHIP_ID        = 0x50
    protected static final int SOFT_RESET     = 0xB6
    protected static final int STATUS_NVM_RDY = 0x02
    protected static final int STATUS_NVM_ERR = 0x04
    protected static final int INT_STATUS_DRDY = 0x01

    protected final Connection connection
    protected final int busType

    protected int odr = 0x1C
    protected int pwrMode = 0x01

    Bmp581Minimal(Connection connection) throws Exception {
        this(connection, 0x46, BUS_I2C)
    }

    Bmp581Minimal(Connection connection, int addr) throws Exception {
        this(connection, addr, BUS_I2C)
    }

    Bmp581Minimal(Connection connection, int addr, int busType) throws Exception {
        this.connection = connection
        this.busType = busType
        init(addr)
    }

    protected void init(int addr) throws Exception {
        if (busType == BUS_SPI) {
            connection.write([(byte) (REG_CHIP_ID | 0x80)] as byte[])
            connection.read(1)
        }
        byte[] id = connection.writeRead([(byte) REG_CHIP_ID] as byte[], 1)
        int chipId = id[0] & 0xFF
        if (chipId != CHIP_ID) {
            throw new IOException("BMP581 not found: expected 0x50, got 0x${Integer.toHexString(chipId)}")
        }
        for (int i = 0; i < 50; i++) {
            byte[] st = connection.writeRead([(byte) REG_STATUS] as byte[], 1)
            int s = st[0] & 0xFF
            if ((s & STATUS_NVM_RDY) != 0 && (s & STATUS_NVM_ERR) == 0) break
            Thread.sleep(2)
        }
        connection.writeRead([(byte) REG_INT_STATUS] as byte[], 1)
        try {
            connection.write([(byte) REG_CMD, (byte) SOFT_RESET] as byte[])
        } catch (IOException ignored) { /* expected NACK */ }
        Thread.sleep(2)
        for (int i = 0; i < 50; i++) {
            byte[] st = connection.writeRead([(byte) REG_STATUS] as byte[], 1)
            int s = st[0] & 0xFF
            if ((s & STATUS_NVM_RDY) != 0 && (s & STATUS_NVM_ERR) == 0) break
            Thread.sleep(2)
        }
        connection.writeRead([(byte) REG_INT_STATUS] as byte[], 1)
        writeReg(REG_OSR_CONFIG, 0x40)
        writeReg(REG_ODR_CONFIG, 0x71)
    }

    protected void writeReg(int reg, int value) throws Exception {
        int addr = (busType == BUS_SPI) ? (reg & 0x7F) : reg
        connection.write([(byte) addr, (byte) (value & 0xFF)] as byte[])
    }

    protected void waitForced() throws Exception {
        if (pwrMode != 2) return
        for (int i = 0; i < 200; i++) {
            byte[] st = connection.writeRead([(byte) REG_INT_STATUS] as byte[], 1)
            if ((st[0] & INT_STATUS_DRDY) != 0) return
            Thread.sleep(5)
        }
    }

    double pressure() throws Exception {
        waitForced()
        byte[] buf = connection.writeRead([(byte) REG_PRESS_XLSB] as byte[], 3)
        int raw = ((buf[2] & 0xFF) << 16) | ((buf[1] & 0xFF) << 8) | (buf[0] & 0xFF)
        int signedRaw = (raw & 0x800000) != 0 ? (raw - 0x1000000) : raw
        return signedRaw / 64.0d
    }

    double temperature() throws Exception {
        waitForced()
        byte[] buf = connection.writeRead([(byte) REG_TEMP_XLSB] as byte[], 3)
        int raw = ((buf[2] & 0xFF) << 16) | ((buf[1] & 0xFF) << 8) | (buf[0] & 0xFF)
        int signedRaw = (raw & 0x800000) != 0 ? (raw - 0x1000000) : raw
        return signedRaw / 65536.0d
    }

    double[] both() throws Exception {
        waitForced()
        byte[] buf = connection.writeRead([(byte) REG_TEMP_XLSB] as byte[], 6)
        int rawT = ((buf[2] & 0xFF) << 16) | ((buf[1] & 0xFF) << 8) | (buf[0] & 0xFF)
        int rawP = ((buf[5] & 0xFF) << 16) | ((buf[4] & 0xFF) << 8) | (buf[3] & 0xFF)
        int signedT = (rawT & 0x800000) != 0 ? (rawT - 0x1000000) : rawT
        int signedP = (rawP & 0x800000) != 0 ? (rawP - 0x1000000) : rawP
        return [(signedP / 64.0d), (signedT / 65536.0d)] as double[]
    }
}