package it.uhde.periph.chips.other

import groovy.transform.CompileStatic
import it.uhde.periph.connection.Connection

/**
 * MPR121 — proximity capacitive touch sensor controller (minimal driver).
 *
 * Provides 12-electrode touch/release detection with no configuration
 * beyond the connection. Performs soft reset, applies default
 * touch/release thresholds, enables the chip's automatic CDC/CDT
 * configuration, and enters Run Mode on all 12 electrodes at construction.
 *
 * Default I²C address: 0x5A (selects via ADDR pin: 0x5A/0x5B/0x5C/0x5D).
 *
 * Configuration defaults:
 * - Touch threshold: 12 for ELE0..ELE11
 * - Release threshold: 6 for ELE0..ELE11
 * - MHDR = NHDR = MHDF = NHDF = 1
 * - CDC_CONFIG: 0x10 (16 µA global CDC, FFI = 6 samples)
 * - CDT_CONFIG: 0x24 (CDT = 1 µS, ESI = 16 ms sample interval)
 * - AUTOCONFIG0: 0x0B
 * - USL = 0xC9, TL = 0xB4, LSL = 0x82 (3.3 V VDD)
 * - ECR: 0x8C (all 12 electrodes)
 */
@CompileStatic
class Mpr121Minimal {

    static final int REG_ELE0_7_TOUCH  = 0x00
    static final int REG_ELE8_PROX_TCH = 0x01
    static final int REG_ELE0_7_OOR    = 0x02
    static final int REG_MHDR          = 0x2B
    static final int REG_NHDR          = 0x2C
    static final int REG_MHDF          = 0x2F
    static final int REG_NHDF          = 0x30
    static final int REG_E0TTH         = 0x41
    static final int REG_E0RTH         = 0x42
    static final int REG_EPROXTTH      = 0x59
    static final int REG_EPROXRTH      = 0x5A
    static final int REG_DEBOUNCE      = 0x5B
    static final int REG_CDC_CONFIG    = 0x5C
    static final int REG_CDT_CONFIG    = 0x5D
    static final int REG_ECR           = 0x5E
    static final int REG_AUTOCONFIG0   = 0x7B
    static final int REG_AUTOCONFIG1   = 0x7C
    static final int REG_USL           = 0x7D
    static final int REG_LSL           = 0x7E
    static final int REG_TL            = 0x7F
    static final int REG_SRST          = 0x80

    static final int SOFT_RESET_KEY      = 0x63
    static final int TOUCH_DEFAULT       = 12
    static final int RELEASE_DEFAULT     = 6
    static final int CDC_CONFIG_DEFAULT  = 0x10
    static final int CDT_CONFIG_DEFAULT  = 0x24
    static final int AUTOCONFIG0_DEFAULT = 0x0B
    static final int ECR_DEFAULT         = 0x8C
    static final int USL_3V3             = 0xC9
    static final int TL_3V3              = 0xB4
    static final int LSL_3V3             = 0x82

    protected final Connection connection

    Mpr121Minimal(Connection connection) {
        this.connection = connection
        softReset()
        writeReg(REG_MHDR, 0x01)
        writeReg(REG_NHDR, 0x01)
        writeReg(REG_MHDF, 0x01)
        writeReg(REG_NHDF, 0x01)
        writeReg(REG_CDC_CONFIG, CDC_CONFIG_DEFAULT)
        writeReg(REG_CDT_CONFIG, CDT_CONFIG_DEFAULT)
        writeReg(REG_USL, USL_3V3)
        writeReg(REG_TL, TL_3V3)
        writeReg(REG_LSL, LSL_3V3)
        writeReg(REG_AUTOCONFIG0, AUTOCONFIG0_DEFAULT)
        for (int n = 0; n < 12; n++) {
            writeReg(REG_E0TTH + 2 * n, TOUCH_DEFAULT)
            writeReg(REG_E0RTH + 2 * n, RELEASE_DEFAULT)
        }
        writeReg(REG_ECR, ECR_DEFAULT)
    }

    /** Read the 12-bit electrode touch bitmask. */
    int touched() {
        byte[] buf = connection.writeRead([REG_ELE0_7_TOUCH] as byte[], 2)
        return (buf[0] & 0xFF) | (((buf[1] & 0xFF) & 0x0F) << 8)
    }

    /** Check whether a single electrode is currently touched. */
    boolean isTouched(int electrode) {
        if (electrode < 0 || electrode > 11) {
            throw new IllegalArgumentException("electrode must be in 0..11")
        }
        return (touched() & (1 << electrode)) != 0
    }

    protected void softReset() {
        writeReg(REG_SRST, SOFT_RESET_KEY)
        Thread.sleep(1)
    }

    protected void writeReg(int reg, int value) {
        byte[] buf = [(reg & 0xFF) as byte, (value & 0xFF) as byte]
        connection.write(buf)
    }

    protected int readReg(int reg) {
        byte[] buf = connection.writeRead([(reg & 0xFF) as byte] as byte[], 1)
        return buf[0] & 0xFF
    }

    protected int readReg16(int reg) {
        byte[] buf = connection.writeRead([(reg & 0xFF) as byte] as byte[], 2)
        return (buf[0] & 0xFF) | (((buf[1] & 0xFF) & 0x03) << 8)
    }
}
