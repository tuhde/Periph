package it.uhde.periph.chips.light;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * APDS-9930 — digital ambient light and proximity sensor (minimal driver).
 *
 * <p>Provides illuminance (lux, IR-compensated) and raw proximity count
 * with no configuration required beyond the connection. Both engines are
 * enabled at construction with sensible defaults that give stable readings
 * under typical indoor/outdoor lighting.
 *
 * <p>Default I²C address: 0x39 (fixed).
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>ATIME: 0xDB (101 ms integration — rejects 50/60 Hz fluorescent flicker)</li>
 *   <li>PTIME: 0xFF (2.73 ms proximity ADC time, datasheet default)</li>
 *   <li>PPULSE: 0x08 (8 LED pulses — factory-calibrated for 100 mm range)</li>
 *   <li>CONTROL: 0x20 (PDIODE=Ch1, PDRIVE=100 mA, PGAIN=1x, AGAIN=1x)</li>
 *   <li>ENABLE: 0x07 (PON + AEN + PEN; wait timer and interrupts disabled)</li>
 * </ul>
 */
public class Apds9930Minimal {

    protected static final int REG_ENABLE   = 0x00;
    protected static final int REG_ATIME    = 0x01;
    protected static final int REG_PTIME    = 0x02;
    protected static final int REG_WTIME    = 0x03;
    protected static final int REG_AILTL    = 0x04;
    protected static final int REG_AILTH    = 0x05;
    protected static final int REG_AIHTL    = 0x06;
    protected static final int REG_AIHTH    = 0x07;
    protected static final int REG_PILTL    = 0x08;
    protected static final int REG_PILTH    = 0x09;
    protected static final int REG_PIHTL    = 0x0A;
    protected static final int REG_PIHTH    = 0x0B;
    protected static final int REG_PERS     = 0x0C;
    protected static final int REG_CONFIG   = 0x0D;
    protected static final int REG_PPULSE   = 0x0E;
    protected static final int REG_CONTROL  = 0x0F;
    protected static final int REG_ID       = 0x12;
    protected static final int REG_STATUS   = 0x13;
    protected static final int REG_CH0DATAL = 0x14;
    protected static final int REG_CH1DATAL = 0x16;
    protected static final int REG_PDATAL   = 0x18;
    protected static final int REG_POFFSET  = 0x1E;

    protected static final int CFN_CLEAR_PROXIMITY = 0x05;
    protected static final int CFN_CLEAR_ALS       = 0x06;
    protected static final int CFN_CLEAR_BOTH      = 0x07;
    protected static final int CMD_SPECIAL         = 0xE0;

    protected static final int ATIME_DEFAULT   = 0xDB;
    protected static final int PTIME_DEFAULT   = 0xFF;
    protected static final int PPULSE_DEFAULT  = 0x08;
    protected static final int CONTROL_DEFAULT = 0x20;
    protected static final int ENABLE_DEFAULT  = 0x07;

    protected static final int CMD_WRITE = 0x80;
    protected static final int CMD_READ  = 0xA0;

    protected final Connection connection;

    /**
     * Construct the driver.
     *
     * @param connection I²C connection bound to the APDS-9930 device address (0x39)
     * @throws IOException on I²C error
     */
    public Apds9930Minimal(Connection connection) throws IOException {
        this.connection = connection;
        sleep(6);
        int id = readReg(REG_ID);
        if (id != 0x39) {
            throw new IOException("APDS-9930 not found (ID=0x" + Integer.toHexString(id) + ", expected 0x39)");
        }
        writeReg(REG_ENABLE, 0x00);
        writeReg(REG_ATIME, ATIME_DEFAULT);
        writeReg(REG_PTIME, PTIME_DEFAULT);
        writeReg(REG_PPULSE, PPULSE_DEFAULT);
        writeReg(REG_CONTROL, CONTROL_DEFAULT);
        writeReg(REG_ENABLE, ENABLE_DEFAULT);
        sleep(12);
    }

    /**
     * Read the device ID register (expect 0x39).
     *
     * @return raw ID register byte
     * @throws IOException on I²C error
     */
    public int chipId() throws IOException {
        return readReg(REG_ID);
    }

    /**
     * Read the ambient illuminance.
     *
     * Uses Ch0 (visible + IR) and Ch1 (IR-only) to compensate for the
     * IR component of ambient light, then applies the open-air lux
     * coefficients from the datasheet.
     *
     * @return illuminance in lux
     * @throws IOException on I²C error
     */
    public float lux() throws IOException {
        int ch0 = readReg16(REG_CH0DATAL);
        int ch1 = readReg16(REG_CH1DATAL);
        int ctrl = readReg(REG_CONTROL);
        int cfg  = readReg(REG_CONFIG);
        int atime = readReg(REG_ATIME);
        float alsitMs = 2.73f * (256 - atime);
        float againX = againFactor(ctrl & 0x03, (cfg & 0x04) != 0);
        float iac1 = ch0 - 1.862f * ch1;
        float iac2 = 0.746f * ch0 - 1.291f * ch1;
        float iac = iac1;
        if (iac2 > iac) iac = iac2;
        if (iac < 0.0f) iac = 0.0f;
        float lpc = (0.49f * 52.0f) / (alsitMs * againX);
        return iac * lpc;
    }

    /**
     * Read the proximity ADC count.
     *
     * Higher counts mean a closer object. Realistically limited to
     * 10 bits (0-1023) at the default PTIME=0xFF (one ADC cycle).
     *
     * @return raw 16-bit proximity count
     * @throws IOException on I²C error
     */
    public int proximity() throws IOException {
        return readReg16(REG_PDATAL);
    }

    protected void writeReg(int reg, int value) throws IOException {
        byte[] buf = new byte[] { (byte) cmdWrite(reg), (byte) (value & 0xFF) };
        connection.write(buf);
    }

    protected int readReg(int reg) throws IOException {
        byte[] buf = new byte[1];
        connection.writeRead(new byte[] { (byte) cmdRead(reg) }, buf);
        return buf[0] & 0xFF;
    }

    protected int readReg16(int reg) throws IOException {
        byte[] buf = new byte[2];
        connection.writeRead(new byte[] { (byte) cmdRead(reg) }, buf);
        return ((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF);
    }

    protected void special(int functionCode) throws IOException {
        connection.write(new byte[] { (byte) cmdSpecial(functionCode) });
    }

    protected static int cmdWrite(int reg) { return CMD_WRITE | (reg & 0x1F); }
    protected static int cmdRead(int reg)  { return CMD_READ  | (reg & 0x1F); }
    protected static int cmdSpecial(int f) { return CMD_SPECIAL | (f & 0x1F); }

    private static float againFactor(int againIdx, boolean agl) {
        if (!agl) {
            float[] t = { 1.0f, 8.0f, 16.0f, 120.0f };
            return t[againIdx & 0x03];
        }
        float[] t = { 1.0f / 6.0f, 8.0f / 6.0f, 16.0f / 6.0f, 20.0f };
        return t[againIdx & 0x03];
    }

    private static void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}