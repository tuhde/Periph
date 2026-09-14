package it.uhde.periph.chips.other

import it.uhde.periph.connection.Connection
import java.io.IOException

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
open class Mpr121Minimal @JvmOverloads constructor(
    protected val connection: Connection,
) {
    /**
     * Read the 12-bit electrode touch bitmask.
     *
     * Reads ELE0_7_TOUCH and ELE8_PROX_TOUCH as a coherent two-byte snapshot
     * from register 0x00; ELEPROX is masked out.
     *
     * @return 12-bit bitmask; bit n = 1 if ELEn is currently touched
     */
    @Throws(IOException::class)
    fun touched(): Int {
        val buf = connection.writeRead(byteArrayOf(REG_ELE0_7_TOUCH.toByte()), 2)
        return (buf[0].toInt() and 0xFF) or (((buf[1].toInt() and 0xFF) and 0x0F) shl 8)
    }

    /**
     * Check whether a single electrode is currently touched.
     *
     * @param electrode electrode index 0-11
     */
    @Throws(IOException::class)
    fun isTouched(electrode: Int): Boolean {
        require(electrode in 0..11) { "electrode must be in 0..11" }
        return (touched() and (1 shl electrode)) != 0
    }

    @Throws(IOException::class)
    protected fun writeReg(reg: Int, value: Int) {
        val buf = byteArrayOf((reg and 0xFF).toByte(), (value and 0xFF).toByte())
        connection.write(buf)
    }

    @Throws(IOException::class)
    protected fun readReg(reg: Int): Int {
        val buf = connection.writeRead(byteArrayOf((reg and 0xFF).toByte()), 1)
        return buf[0].toInt() and 0xFF
    }

    @Throws(IOException::class)
    protected fun readReg16(reg: Int): Int {
        val buf = connection.writeRead(byteArrayOf((reg and 0xFF).toByte()), 2)
        return (buf[0].toInt() and 0xFF) or (((buf[1].toInt() and 0xFF) and 0x03) shl 8)
    }

    @Throws(IOException::class)
    protected fun softReset() {
        writeReg(REG_SRST, SOFT_RESET_KEY)
        Thread.sleep(1)
    }

    init {
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
        for (n in 0 until 12) {
            writeReg(REG_E0TTH + 2 * n, TOUCH_DEFAULT)
            writeReg(REG_E0RTH + 2 * n, RELEASE_DEFAULT)
        }
        writeReg(REG_ECR, ECR_DEFAULT)
    }

    companion object {
        const val REG_ELE0_7_TOUCH = 0x00
        const val REG_ELE8_PROX_TCH = 0x01
        const val REG_ELE0_7_OOR = 0x02
        const val REG_MHDR = 0x2B
        const val REG_NHDR = 0x2C
        const val REG_MHDF = 0x2F
        const val REG_NHDF = 0x30
        const val REG_E0TTH = 0x41
        const val REG_E0RTH = 0x42
        const val REG_EPROXTTH = 0x59
        const val REG_EPROXRTH = 0x5A
        const val REG_DEBOUNCE = 0x5B
        const val REG_CDC_CONFIG = 0x5C
        const val REG_CDT_CONFIG = 0x5D
        const val REG_ECR = 0x5E
        const val REG_AUTOCONFIG0 = 0x7B
        const val REG_AUTOCONFIG1 = 0x7C
        const val REG_USL = 0x7D
        const val REG_LSL = 0x7E
        const val REG_TL = 0x7F
        const val REG_SRST = 0x80

        const val SOFT_RESET_KEY = 0x63
        const val TOUCH_DEFAULT = 12
        const val RELEASE_DEFAULT = 6
        const val CDC_CONFIG_DEFAULT = 0x10
        const val CDT_CONFIG_DEFAULT = 0x24
        const val AUTOCONFIG0_DEFAULT = 0x0B
        const val ECR_DEFAULT = 0x8C
        const val USL_3V3 = 0xC9
        const val TL_3V3 = 0xB4
        const val LSL_3V3 = 0x82
    }
}
