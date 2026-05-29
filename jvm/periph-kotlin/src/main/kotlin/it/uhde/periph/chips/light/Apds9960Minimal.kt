package it.uhde.periph.chips.light

import it.uhde.periph.transport.Transport

/**
 * APDS-9960 — digital proximity, ambient light, RGB and gesture sensor (minimal driver).
 *
 * Provides ambient light and color (RGBC) readings with no configuration
 * beyond the transport. The ALS/Color engine is enabled at construction
 * with sensible defaults.
 *
 * Default I²C address: 0x39 (fixed).
 *
 * ## Configuration defaults
 * - ATIME: 0xB6 (72 cycles, ~200 ms integration, max count 65535)
 * - AGAIN: 1 (4x ALS gain)
 * - CONFIG2: 0x01 (LED_BOOST=100%, reserved bit 0 set)
 * - PON + AEN enabled; no wait, proximity, gesture, or interrupts
 */
open class Apds9960Minimal(
    protected val transport: Transport
) {
    companion object {
        const val REG_ENABLE     = 0x80
        const val REG_ATIME      = 0x81
        const val REG_WTIME      = 0x83
        const val REG_AILTL      = 0x84
        const val REG_AILTH      = 0x85
        const val REG_AIHTL      = 0x86
        const val REG_AIHTH      = 0x87
        const val REG_PILT       = 0x89
        const val REG_PIHT       = 0x8B
        const val REG_PERS       = 0x8C
        const val REG_CONFIG1    = 0x8D
        const val REG_PPULSE     = 0x8E
        const val REG_CONTROL    = 0x8F
        const val REG_CONFIG2    = 0x90
        const val REG_ID         = 0x92
        const val REG_STATUS     = 0x93
        const val REG_CDATAL     = 0x94
        const val REG_RDATAL     = 0x96
        const val REG_GDATAL     = 0x98
        const val REG_BDATAL     = 0x9A
        const val REG_PDATA      = 0x9C
        const val REG_POFFSET_UR = 0x9D
        const val REG_POFFSET_DL = 0x9E
        const val REG_CONFIG3    = 0x9F
        const val REG_GPENTH     = 0xA0
        const val REG_GEXTH      = 0xA1
        const val REG_GCONF1     = 0xA2
        const val REG_GCONF2     = 0xA3
        const val REG_GOFFSET_U  = 0xA4
        const val REG_GOFFSET_D  = 0xA5
        const val REG_GPULSE     = 0xA6
        const val REG_GOFFSET_L  = 0xA7
        const val REG_GOFFSET_R  = 0xA9
        const val REG_GCONF3     = 0xAA
        const val REG_GCONF4     = 0xAB
        const val REG_GFLVL      = 0xAE
        const val REG_GSTATUS    = 0xAF
        const val REG_PICLEAR    = 0xE5
        const val REG_CICLEAR    = 0xE6
        const val REG_AICLEAR    = 0xE7
        const val REG_GFIFO_U    = 0xFC

        const val ATIME_DEFAULT   = 0xB6
        const val CONTROL_DEFAULT = 0x01
        const val CONFIG2_DEFAULT = 0x01
    }

    init {
        try { Thread.sleep(6) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        val id = readReg(REG_ID)
        if (id != 0xAB) throw java.io.IOException("APDS-9960 not found (ID=0x${id.toString(16)}, expected 0xAB)")
        writeReg(REG_ENABLE, 0x00)
        writeReg(REG_ATIME, ATIME_DEFAULT)
        writeReg(REG_CONTROL, CONTROL_DEFAULT)
        writeReg(REG_CONFIG2, CONFIG2_DEFAULT)
        writeReg(REG_ENABLE, 0x03)
        try { Thread.sleep(210) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
    }

    /**
     * Read the clear (unfiltered) channel.
     *
     * @return raw clear channel count, 0-65535
     */
    fun colorClear(): Int = readReg16LE(REG_CDATAL)

    /**
     * Read the red channel.
     *
     * Burst-reads all 8 bytes from CDATAL to trigger the atomic latch.
     *
     * @return raw red channel count, 0-65535
     */
    fun colorRed(): Int {
        val raw = transport.writeRead(byteArrayOf(REG_CDATAL.toByte()), 8)
        return (raw[2].toInt() and 0xFF) or ((raw[3].toInt() and 0xFF) shl 8)
    }

    /**
     * Read the green channel.
     *
     * Burst-reads all 8 bytes from CDATAL to trigger the atomic latch.
     *
     * @return raw green channel count, 0-65535
     */
    fun colorGreen(): Int {
        val raw = transport.writeRead(byteArrayOf(REG_CDATAL.toByte()), 8)
        return (raw[4].toInt() and 0xFF) or ((raw[5].toInt() and 0xFF) shl 8)
    }

    /**
     * Read the blue channel.
     *
     * Burst-reads all 8 bytes from CDATAL to trigger the atomic latch.
     *
     * @return raw blue channel count, 0-65535
     */
    fun colorBlue(): Int {
        val raw = transport.writeRead(byteArrayOf(REG_CDATAL.toByte()), 8)
        return (raw[6].toInt() and 0xFF) or ((raw[7].toInt() and 0xFF) shl 8)
    }

    /**
     * Read all four RGBC channels in one burst.
     *
     * Reading CDATAL at 0x94 atomically latches all eight bytes 0x94-0x9B.
     *
     * @return array of [clear, red, green, blue] each 0-65535
     */
    fun color(): IntArray {
        val raw = transport.writeRead(byteArrayOf(REG_CDATAL.toByte()), 8)
        val c = (raw[0].toInt() and 0xFF) or ((raw[1].toInt() and 0xFF) shl 8)
        val r = (raw[2].toInt() and 0xFF) or ((raw[3].toInt() and 0xFF) shl 8)
        val g = (raw[4].toInt() and 0xFF) or ((raw[5].toInt() and 0xFF) shl 8)
        val b = (raw[6].toInt() and 0xFF) or ((raw[7].toInt() and 0xFF) shl 8)
        return intArrayOf(c, r, g, b)
    }

    protected fun writeReg(reg: Int, value: Int) {
        transport.write(byteArrayOf(reg.toByte(), value.toByte()))
    }

    protected fun readReg(reg: Int): Int {
        val b = transport.writeRead(byteArrayOf(reg.toByte()), 1)
        return b[0].toInt() and 0xFF
    }

    protected fun readReg16LE(reg: Int): Int {
        val b = transport.writeRead(byteArrayOf(reg.toByte()), 2)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }
}
