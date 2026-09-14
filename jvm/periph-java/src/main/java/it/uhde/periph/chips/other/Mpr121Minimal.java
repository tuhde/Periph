package it.uhde.periph.chips.other;

import it.uhde.periph.connection.Connection;

import java.io.IOException;

/**
 * MPR121 — proximity capacitive touch sensor controller (minimal driver).
 *
 * <p>Provides 12-electrode touch/release detection with no configuration
 * beyond the connection. Performs soft reset, applies default
 * touch/release thresholds, enables the chip's automatic CDC/CDT
 * configuration, and enters Run Mode on all 12 electrodes at construction.
 *
 * <p>Default I²C address: 0x5A (selects via ADDR pin: 0x5A/0x5B/0x5C/0x5D).
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>Touch threshold: 12 for ELE0..ELE11</li>
 *   <li>Release threshold: 6 for ELE0..ELE11</li>
 *   <li>MHDR = NHDR = MHDF = NHDF = 1 (baseline filter defaults)</li>
 *   <li>CDC_CONFIG: 0x10 (16 µA global CDC, FFI = 6 samples)</li>
 *   <li>CDT_CONFIG: 0x24 (CDT = 1 µS, ESI = 16 ms sample interval)</li>
 *   <li>AUTOCONFIG0: 0x0B (FFI=00, BVA=10, ARE=1, ACE=1)</li>
 *   <li>USL = 0xC9, TL = 0xB4, LSL = 0x82 (3.3 V VDD)</li>
 *   <li>ECR: 0x8C (CL=10, ELEPROX_EN=00, ELE_EN=12 -> all 12)</li>
 * </ul>
 */
public class Mpr121Minimal {

    protected static final int REG_ELE0_7_TOUCH  = 0x00;
    protected static final int REG_ELE8_PROX_TCH = 0x01;
    protected static final int REG_ELE0_7_OOR    = 0x02;
    protected static final int REG_MHDR          = 0x2B;
    protected static final int REG_NHDR          = 0x2C;
    protected static final int REG_MHDF          = 0x2F;
    protected static final int REG_NHDF          = 0x30;
    protected static final int REG_E0TTH         = 0x41;
    protected static final int REG_E0RTH         = 0x42;
    protected static final int REG_EPROXTTH      = 0x59;
    protected static final int REG_EPROXRTH      = 0x5A;
    protected static final int REG_DEBOUNCE      = 0x5B;
    protected static final int REG_CDC_CONFIG    = 0x5C;
    protected static final int REG_CDT_CONFIG    = 0x5D;
    protected static final int REG_ECR           = 0x5E;
    protected static final int REG_AUTOCONFIG0   = 0x7B;
    protected static final int REG_AUTOCONFIG1   = 0x7C;
    protected static final int REG_USL           = 0x7D;
    protected static final int REG_LSL           = 0x7E;
    protected static final int REG_TL            = 0x7F;
    protected static final int REG_SRST          = 0x80;

    protected static final int SOFT_RESET_KEY      = 0x63;
    protected static final int TOUCH_DEFAULT       = 12;
    protected static final int RELEASE_DEFAULT     = 6;
    protected static final int CDC_CONFIG_DEFAULT  = 0x10;
    protected static final int CDT_CONFIG_DEFAULT  = 0x24;
    protected static final int AUTOCONFIG0_DEFAULT = 0x0B;
    protected static final int ECR_DEFAULT         = 0x8C;
    protected static final int USL_3V3             = 0xC9;
    protected static final int TL_3V3              = 0xB4;
    protected static final int LSL_3V3             = 0x82;

    protected final Connection connection;

    /**
     * Construct the driver.
     *
     * @param connection I²C connection bound to the MPR121 device address (typically 0x5A)
     * @throws IOException on I²C error
     */
    public Mpr121Minimal(Connection connection) throws IOException {
        this.connection = connection;
        softReset();
        writeReg(REG_MHDR, 0x01);
        writeReg(REG_NHDR, 0x01);
        writeReg(REG_MHDF, 0x01);
        writeReg(REG_NHDF, 0x01);
        writeReg(REG_CDC_CONFIG, CDC_CONFIG_DEFAULT);
        writeReg(REG_CDT_CONFIG, CDT_CONFIG_DEFAULT);
        writeReg(REG_USL, USL_3V3);
        writeReg(REG_TL,  TL_3V3);
        writeReg(REG_LSL, LSL_3V3);
        writeReg(REG_AUTOCONFIG0, AUTOCONFIG0_DEFAULT);
        for (int n = 0; n < 12; n++) {
            writeReg(REG_E0TTH + 2 * n, TOUCH_DEFAULT);
            writeReg(REG_E0RTH + 2 * n, RELEASE_DEFAULT);
        }
        writeReg(REG_ECR, ECR_DEFAULT);
    }

    /**
     * Read the 12-bit electrode touch bitmask.
     *
     * Reads ELE0_7_TOUCH and ELE8_PROX_TOUCH as a coherent two-byte snapshot
     * from register 0x00; ELEPROX is masked out.
     *
     * @return 12-bit bitmask; bit n = 1 if ELEn is currently touched
     * @throws IOException on I²C error
     */
    public int touched() throws IOException {
        byte[] buf = connection.writeRead(new byte[] { REG_ELE0_7_TOUCH }, 2);
        return (buf[0] & 0xFF) | (((buf[1] & 0xFF) & 0x0F) << 8);
    }

    /**
     * Check whether a single electrode is currently touched.
     *
     * @param electrode electrode index 0-11
     * @return true if ELE_electrode is currently touched
     * @throws IOException on I²C error
     */
    public boolean isTouched(int electrode) throws IOException {
        if (electrode < 0 || electrode > 11) {
            throw new IllegalArgumentException("electrode must be in 0..11");
        }
        return (touched() & (1 << electrode)) != 0;
    }

    protected void softReset() throws IOException {
        writeReg(REG_SRST, SOFT_RESET_KEY);
        sleep(1);
    }

    protected void writeReg(int reg, int value) throws IOException {
        byte[] buf = new byte[] { (byte) (reg & 0xFF), (byte) (value & 0xFF) };
        connection.write(buf);
    }

    protected int readReg(int reg) throws IOException {
        byte[] buf = connection.writeRead(new byte[] { (byte) (reg & 0xFF) }, 1);
        return buf[0] & 0xFF;
    }

    protected int readReg16(int reg) throws IOException {
        byte[] buf = connection.writeRead(new byte[] { (byte) (reg & 0xFF) }, 2);
        return (buf[0] & 0xFF) | (((buf[1] & 0xFF) & 0x03) << 8);
    }

    private static void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
