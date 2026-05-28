package it.uhde.periph.chips.light

import groovy.transform.CompileStatic
import it.uhde.periph.transport.Transport

/**
 * APDS-9960 — digital proximity, ambient light, RGB and gesture sensor (minimal driver).
 *
 * <p>Provides ambient light and color (RGBC) readings with no configuration
 * beyond the transport. The ALS/Color engine is enabled at construction
 * with sensible defaults.
 *
 * <p>Default I²C address: 0x39 (fixed).
 *
 * <h2>Configuration defaults</h2>
 * <ul>
 *   <li>ATIME: 0xB6 (72 cycles, ~200 ms integration, max count 65535)</li>
 *   <li>AGAIN: 1 (4x ALS gain)</li>
 *   <li>CONFIG2: 0x01 (LED_BOOST=100%, reserved bit 0 set)</li>
 *   <li>PON + AEN enabled; no wait, proximity, gesture, or interrupts</li>
 * </ul>
 */
@CompileStatic
class Apds9960Minimal {

    protected static final int REG_ENABLE     = 0x80
    protected static final int REG_ATIME      = 0x81
    protected static final int REG_WTIME      = 0x83
    protected static final int REG_AILTL      = 0x84
    protected static final int REG_AILTH      = 0x85
    protected static final int REG_AIHTL      = 0x86
    protected static final int REG_AIHTH      = 0x87
    protected static final int REG_PILT       = 0x89
    protected static final int REG_PIHT       = 0x8B
    protected static final int REG_PERS       = 0x8C
    protected static final int REG_CONFIG1    = 0x8D
    protected static final int REG_PPULSE     = 0x8E
    protected static final int REG_CONTROL    = 0x8F
    protected static final int REG_CONFIG2    = 0x90
    protected static final int REG_ID         = 0x92
    protected static final int REG_STATUS     = 0x93
    protected static final int REG_CDATAL     = 0x94
    protected static final int REG_RDATAL     = 0x96
    protected static final int REG_GDATAL     = 0x98
    protected static final int REG_BDATAL     = 0x9A
    protected static final int REG_PDATA      = 0x9C
    protected static final int REG_POFFSET_UR = 0x9D
    protected static final int REG_POFFSET_DL = 0x9E
    protected static final int REG_CONFIG3    = 0x9F
    protected static final int REG_GPENTH     = 0xA0
    protected static final int REG_GEXTH      = 0xA1
    protected static final int REG_GCONF1     = 0xA2
    protected static final int REG_GCONF2     = 0xA3
    protected static final int REG_GOFFSET_U  = 0xA4
    protected static final int REG_GOFFSET_D  = 0xA5
    protected static final int REG_GPULSE     = 0xA6
    protected static final int REG_GOFFSET_L  = 0xA7
    protected static final int REG_GOFFSET_R  = 0xA9
    protected static final int REG_GCONF3     = 0xAA
    protected static final int REG_GCONF4     = 0xAB
    protected static final int REG_GFLVL      = 0xAE
    protected static final int REG_GSTATUS    = 0xAF
    protected static final int REG_PICLEAR    = 0xE5
    protected static final int REG_CICLEAR    = 0xE6
    protected static final int REG_AICLEAR    = 0xE7
    protected static final int REG_GFIFO_U    = 0xFC

    protected static final int ATIME_DEFAULT   = 0xB6
    protected static final int CONTROL_DEFAULT = 0x01
    protected static final int CONFIG2_DEFAULT = 0x01

    protected final Transport transport

    /**
     * Construct the driver.
     *
     * @param transport I²C transport bound to the APDS-9960 device address (0x39)
     */
    Apds9960Minimal(Transport transport) {
        this.transport = transport
        Thread.sleep(6)
        int id = readReg(REG_ID)
        if (id != 0xAB) throw new IOException("APDS-9960 not found (ID=0x${Integer.toHexString(id)}, expected 0xAB)")
        writeReg(REG_ENABLE, 0x00)
        writeReg(REG_ATIME, ATIME_DEFAULT)
        writeReg(REG_CONTROL, CONTROL_DEFAULT)
        writeReg(REG_CONFIG2, CONFIG2_DEFAULT)
        writeReg(REG_ENABLE, 0x03)
        Thread.sleep(210)
    }

    /**
     * Read the clear (unfiltered) channel.
     *
     * @return raw clear channel count, 0-65535
     */
    int colorClear() {
        readReg16LE(REG_CDATAL)
    }

    /**
     * Read the red channel.
     *
     * @return raw red channel count, 0-65535
     */
    int colorRed() {
        readReg16LE(REG_RDATAL)
    }

    /**
     * Read the green channel.
     *
     * @return raw green channel count, 0-65535
     */
    int colorGreen() {
        readReg16LE(REG_GDATAL)
    }

    /**
     * Read the blue channel.
     *
     * @return raw blue channel count, 0-65535
     */
    int colorBlue() {
        readReg16LE(REG_BDATAL)
    }

    /**
     * Read all four RGBC channels in one burst.
     *
     * @return array of [clear, red, green, blue] each 0-65535
     */
    int[] color() {
        byte[] raw = transport.writeRead([(byte) REG_CDATAL] as byte[], 8)
        int c = (raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8)
        int r = (raw[2] & 0xFF) | ((raw[3] & 0xFF) << 8)
        int g = (raw[4] & 0xFF) | ((raw[5] & 0xFF) << 8)
        int b = (raw[6] & 0xFF) | ((raw[7] & 0xFF) << 8)
        [c, r, g, b] as int[]
    }

    protected void writeReg(int reg, int value) {
        transport.write([(byte) reg, (byte) value] as byte[])
    }

    protected int readReg(int reg) {
        byte[] b = transport.writeRead([(byte) reg] as byte[], 1)
        b[0] & 0xFF
    }

    protected int readReg16LE(int reg) {
        byte[] b = transport.writeRead([(byte) reg] as byte[], 2)
        (b[0] & 0xFF) | ((b[1] & 0xFF) << 8)
    }
}
