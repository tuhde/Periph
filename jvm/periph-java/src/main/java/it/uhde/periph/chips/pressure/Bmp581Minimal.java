package it.uhde.periph.chips.pressure;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * BMP581 — digital barometric pressure and temperature sensor (minimal driver).
 *
 * <p>The BMP581 performs factory calibration internally and outputs
 * already-compensated Pa and °C values, so unlike the BMP280 this driver
 * has no calibration NVM to read and no compensation math — just burst
 * reads of the data registers and fixed-point decode.
 *
 * <p>Address: 0x46 (SDO low, default) or 0x47 (SDO high).
 *
 * <p>Default configuration: NORMAL mode at 1 Hz, press_en=1, osr_p=×1,
 * osr_t=×1, IIR bypass, FIFO disabled, INT_SOURCE=0.
 */
public class Bmp581Minimal {

    /** Bus type: I²C (default) — register addresses used unmasked for both reads and writes. */
    public static final int BUS_I2C = 0;
    /** Bus type: SPI — write addresses have bit 7 cleared; reads stay unmasked. */
    public static final int BUS_SPI = 1;

    // Register addresses
    protected static final int REG_CHIP_ID    = 0x01;
    protected static final int REG_STATUS     = 0x28;
    protected static final int REG_INT_STATUS = 0x27;
    protected static final int REG_TEMP_XLSB  = 0x1D;
    protected static final int REG_PRESS_XLSB = 0x20;
    protected static final int REG_OSR_CONFIG = 0x36;
    protected static final int REG_ODR_CONFIG = 0x37;
    protected static final int REG_CMD        = 0x7E;

    protected static final int CHIP_ID       = 0x50;
    protected static final int SOFT_RESET    = 0xB6;
    protected static final int STATUS_NVM_RDY = 0x02;
    protected static final int STATUS_NVM_ERR = 0x04;
    protected static final int INT_STATUS_DRDY = 0x01;

    protected final Connection connection;
    protected final int busType;

    protected int odr = 0x1C;
    protected int pwrMode = 0x01;

    /**
     * Construct the driver at the default address (0x46), verify the chip
     * ID, and apply the default configuration.
     *
     * @param connection I²C connection bound to address 0x46
     * @throws IOException on I²C error or wrong chip ID
     */
    public Bmp581Minimal(Connection connection) throws IOException {
        this(connection, 0x46);
    }

    /**
     * Construct the driver at the given address.
     *
     * @param connection I²C connection bound to the device
     * @param addr      I²C device address (0x46 or 0x47)
     * @throws IOException on I²C error or wrong chip ID
     */
    public Bmp581Minimal(Connection connection, int addr) throws IOException {
        this(connection, addr, BUS_I2C);
    }

    /**
     * Construct the driver with explicit bus type.
     *
     * <p>Pass {@link #BUS_SPI} for SPI — write addresses have bit 7 cleared
     * (reg &amp; 0x7F); reads use the same register constants unmasked.
     *
     * @param connection I²C or SPI connection bound to the device
     * @param addr      I²C device address (0x46 or 0x47); unused for SPI
     * @param busType   {@link #BUS_I2C} or {@link #BUS_SPI}
     * @throws IOException on bus error or wrong chip ID
     */
    public Bmp581Minimal(Connection connection, int addr, int busType) throws IOException {
        this.connection = connection;
        this.busType = busType;

        init(addr);
    }

    /** Runs the chip init sequence: verify ID, soft-reset, configure NORMAL 1 Hz. */
    protected void init(int addr) throws IOException {
        if (busType == BUS_SPI) {
            connection.write(new byte[]{(byte) (REG_CHIP_ID | 0x80)});
            connection.read(1);
        }
        byte[] id = connection.writeRead(new byte[]{(byte) REG_CHIP_ID}, 1);
        int chipId = id[0] & 0xFF;
        if (chipId != CHIP_ID) {
            throw new IOException(
                "BMP581 not found: expected 0x50, got 0x" + Integer.toHexString(chipId));
        }
        for (int i = 0; i < 50; i++) {
            byte[] st = connection.writeRead(new byte[]{(byte) REG_STATUS}, 1);
            int s = st[0] & 0xFF;
            if ((s & STATUS_NVM_RDY) != 0 && (s & STATUS_NVM_ERR) == 0) break;
            try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        connection.writeRead(new byte[]{(byte) REG_INT_STATUS}, 1);
        try {
            connection.write(new byte[]{(byte) REG_CMD, (byte) SOFT_RESET});
        } catch (IOException e) {
            // expected NACK on I²C during reset
        }
        try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        for (int i = 0; i < 50; i++) {
            byte[] st = connection.writeRead(new byte[]{(byte) REG_STATUS}, 1);
            int s = st[0] & 0xFF;
            if ((s & STATUS_NVM_RDY) != 0 && (s & STATUS_NVM_ERR) == 0) break;
            try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        connection.writeRead(new byte[]{(byte) REG_INT_STATUS}, 1);
        writeReg(REG_OSR_CONFIG, 0x40);
        writeReg(REG_ODR_CONFIG, 0x71);
    }

    /**
     * Write a single byte to a register, applying the SPI write-address mask if
     * this driver was constructed with {@link #BUS_SPI}.
     *
     * @param reg   register address (I²C / unmasked form)
     * @param value byte value to write
     * @throws IOException on bus error
     */
    protected void writeReg(int reg, int value) throws IOException {
        int addr = (busType == BUS_SPI) ? (reg & 0x7F) : reg;
        connection.write(new byte[]{(byte) addr, (byte) (value & 0xFF)});
    }

    /** Wait for drdy_data_reg if the chip is in FORCED mode. */
    protected void waitForced() throws IOException {
        if (pwrMode != 2) return;
        for (int i = 0; i < 200; i++) {
            byte[] st = connection.writeRead(new byte[]{(byte) REG_INT_STATUS}, 1);
            if ((st[0] & INT_STATUS_DRDY) != 0) return;
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /** Burst-read 3 bytes from REG_PRESS_XLSB and decode to Pa. */
    protected int readRawPress() throws IOException {
        waitForced();
        byte[] buf = connection.writeRead(new byte[]{(byte) REG_PRESS_XLSB}, 3);
        return ((buf[2] & 0xFF) << 16) | ((buf[1] & 0xFF) << 8) | (buf[0] & 0xFF);
    }

    /** Burst-read 3 bytes from REG_TEMP_XLSB and decode to °C raw (signed 24-bit). */
    protected int readRawTemp() throws IOException {
        waitForced();
        byte[] buf = connection.writeRead(new byte[]{(byte) REG_TEMP_XLSB}, 3);
        return ((buf[2] & 0xFF) << 16) | ((buf[1] & 0xFF) << 8) | (buf[0] & 0xFF);
    }

    /** Decode a 24-bit raw pressure value to Pa (signed). */
    protected double rawToPressure(int raw) {
        int signedRaw = (raw & 0x800000) != 0 ? (raw - 0x1000000) : raw;
        return signedRaw / 64.0;
    }

    /** Decode a 24-bit raw temperature value to °C (signed). */
    protected double rawToTemperature(int raw) {
        int signedRaw = (raw & 0x800000) != 0 ? (raw - 0x1000000) : raw;
        return signedRaw / 65536.0;
    }

    /**
     * Read the pressure.
     *
     * <p>Burst-reads 3 bytes from REG_PRESS_XLSB and decodes to Pa. Self-contained
     * — may be called without a prior {@link #temperature()} call.
     *
     * @return pressure in Pa
     * @throws IOException on I²C error
     */
    public double pressure() throws IOException {
        return rawToPressure(readRawPress());
    }

    /**
     * Read the temperature.
     *
     * <p>Burst-reads 3 bytes from REG_TEMP_XLSB and decodes to °C.
     *
     * @return temperature in °C
     * @throws IOException on I²C error
     */
    public double temperature() throws IOException {
        return rawToTemperature(readRawTemp());
    }

    /**
     * Read both pressure and temperature atomically in a single 6-byte burst.
     *
     * @return double array of length 2: {pressure_Pa, temperature_C}
     * @throws IOException on I²C error
     */
    public double[] both() throws IOException {
        waitForced();
        byte[] buf = connection.writeRead(new byte[]{(byte) REG_TEMP_XLSB}, 6);
        int rawT = ((buf[2] & 0xFF) << 16) | ((buf[1] & 0xFF) << 8) | (buf[0] & 0xFF);
        int rawP = ((buf[5] & 0xFF) << 16) | ((buf[4] & 0xFF) << 8) | (buf[3] & 0xFF);
        return new double[]{rawToPressure(rawP), rawToTemperature(rawT)};
    }
}