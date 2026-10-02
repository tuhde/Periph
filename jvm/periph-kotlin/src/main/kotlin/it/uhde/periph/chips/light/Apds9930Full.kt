package it.uhde.periph.chips.light

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * APDS-9930 — full driver. Extends [Apds9930Minimal] with ALS/proximity
 * configuration, raw channel reads, interrupt thresholds with persistence,
 * status decoding, sleep-after-interrupt, and proximity offset compensation.
 */
class Apds9930Full(connection: RegisterConnection) : Apds9930Minimal(connection) {

    /** STATUS register decoded into named boolean fields. */
    data class Status(val avalid: Boolean, val pvalid: Boolean, val psat: Boolean,
                      val aint: Boolean, val pint: Boolean)

    /**
     * Configure ALS integration time, AGAIN index, and AGL flag.
     *
     * @param atime ATIME register value 0-255
     * @param again ALS gain index 0-3 (0=1x, 1=8x, 2=16x, 3=120x)
     * @param agl true to enable the AGL divide-by-6 gain-level bit
     */
    @Throws(IOException::class)
    fun configureAls(atime: Int, again: Int, agl: Boolean) {
        connection.write(cmdWrite(REG_ATIME), byteArrayOf((atime and 0xFF).toByte()))
        val ctrl = (readReg(REG_CONTROL) and 0xFC) or (again and 0x03)
        connection.write(cmdWrite(REG_CONTROL), byteArrayOf((ctrl).toByte()))
        var cfg = readReg(REG_CONFIG)
        cfg = if (agl) cfg or 0x04 else cfg and 0x04.inv()
        cfg = cfg and 0x06.inv()
        connection.write(cmdWrite(REG_CONFIG), byteArrayOf((cfg).toByte()))
    }

    /**
     * Configure proximity LED pulses, gain, drive, and ADC integration time.
     */
    @Throws(IOException::class)
    fun configureProximity(ppulse: Int, pgain: Int, pdrive: Int, pdl: Boolean, ptime: Int) {
        connection.write(cmdWrite(REG_PPULSE), byteArrayOf((ppulse and 0xFF).toByte()))
        connection.write(cmdWrite(REG_PTIME), byteArrayOf((ptime and 0xFF).toByte()))
        val ctrl = (readReg(REG_CONTROL) and 0x03) or
                   ((pdrive and 0x03) shl 6) or 0x20 or ((pgain and 0x03) shl 2)
        connection.write(cmdWrite(REG_CONTROL), byteArrayOf((ctrl).toByte()))
        var cfg = readReg(REG_CONFIG)
        cfg = if (pdl) cfg or 0x01 else cfg and 0x01.inv()
        cfg = cfg and 0x06.inv()
        connection.write(cmdWrite(REG_CONFIG), byteArrayOf((cfg).toByte()))
    }

    /**
     * Configure wait time and enable the wait timer.
     */
    @Throws(IOException::class)
    fun configureWait(wtime: Int, wlong: Boolean) {
        connection.write(cmdWrite(REG_WTIME), byteArrayOf((wtime and 0xFF).toByte()))
        var cfg = readReg(REG_CONFIG)
        cfg = if (wlong) cfg or 0x02 else cfg and 0x02.inv()
        cfg = cfg and 0x04.inv()
        connection.write(cmdWrite(REG_CONFIG), byteArrayOf((cfg).toByte()))
        connection.write(cmdWrite(REG_ENABLE), byteArrayOf((readReg(REG_ENABLE) or 0x08).toByte()))
    }

    /** Clear WEN in ENABLE (disable the wait timer). */
    @Throws(IOException::class)
    fun disableWait() {
        connection.write(cmdWrite(REG_ENABLE), byteArrayOf((readReg(REG_ENABLE) and 0x08.inv()).toByte()))
    }

    /** Read the raw Ch0 (visible + IR) ADC count. */
    @Throws(IOException::class)
    fun ch0(): Int = readReg16(REG_CH0DATAL)

    /** Read the raw Ch1 (IR-only) ADC count. */
    @Throws(IOException::class)
    fun ch1(): Int = readReg16(REG_CH1DATAL)

    /** Read the STATUS register decoded into named fields. */
    @Throws(IOException::class)
    fun status(): Status {
        val s = readReg(REG_STATUS)
        return Status(
            avalid = (s and 0x01) != 0,
            pvalid = (s and 0x02) != 0,
            psat   = (s and 0x40) != 0,
            aint   = (s and 0x10) != 0,
            pint   = (s and 0x20) != 0
        )
    }

    /**
     * Set ALS interrupt thresholds and enable AIEN. Thresholds are
     * evaluated against raw Ch0 counts, not lux.
     */
    @Throws(IOException::class)
    fun setAlsThresholds(low: Int, high: Int, persistence: Int) {
        val h = if (low > high) low else high
        connection.write(cmdWrite(REG_AILTL), byteArrayOf((low and 0xFF).toByte()))
        connection.write(cmdWrite(REG_AILTH), byteArrayOf(((low shr 8) and 0xFF).toByte()))
        connection.write(cmdWrite(REG_AIHTL), byteArrayOf((h and 0xFF).toByte()))
        connection.write(cmdWrite(REG_AIHTH), byteArrayOf(((h shr 8) and 0xFF).toByte()))
        val pers = (readReg(REG_PERS) and 0xF0) or (persistence and 0x0F)
        connection.write(cmdWrite(REG_PERS), byteArrayOf((pers).toByte()))
        connection.write(cmdWrite(REG_ENABLE), byteArrayOf((readReg(REG_ENABLE) or 0x10).toByte()))
    }

    /**
     * Set proximity interrupt thresholds and enable PIEN.
     */
    @Throws(IOException::class)
    fun setProximityThresholds(low: Int, high: Int, persistence: Int) {
        val h = if (low > high) low else high
        connection.write(cmdWrite(REG_PILTL), byteArrayOf((low and 0xFF).toByte()))
        connection.write(cmdWrite(REG_PILTH), byteArrayOf(((low shr 8) and 0xFF).toByte()))
        connection.write(cmdWrite(REG_PIHTL), byteArrayOf((h and 0xFF).toByte()))
        connection.write(cmdWrite(REG_PIHTH), byteArrayOf(((h shr 8) and 0xFF).toByte()))
        val pers = (readReg(REG_PERS) and 0x0F) or ((persistence and 0x0F) shl 4)
        connection.write(cmdWrite(REG_PERS), byteArrayOf((pers).toByte()))
        connection.write(cmdWrite(REG_ENABLE), byteArrayOf((readReg(REG_ENABLE) or 0x20).toByte()))
    }

    /**
     * Clear pending interrupt(s).
     *
     * @param channel 0=both, 1=ALS, 2=proximity
     */
    @Throws(IOException::class)
    fun clearInterrupt(channel: Int) {
        when (channel) {
            1 -> special(CFN_CLEAR_ALS)
            2 -> special(CFN_CLEAR_PROXIMITY)
            else -> special(CFN_CLEAR_BOTH)
        }
    }

    /**
     * Set the proximity offset (sign-magnitude).
     */
    @Throws(IOException::class)
    fun setProximityOffset(offset: Int) {
        val enc = if (offset >= 0) 0x80 or (offset and 0x7F) else (-offset) and 0x7F
        connection.write(cmdWrite(REG_POFFSET), byteArrayOf((enc).toByte()))
    }

    /** Enable or disable SAI (sleep after interrupt). */
    @Throws(IOException::class)
    fun sleepAfterInterrupt(enable: Boolean) {
        val en = readReg(REG_ENABLE)
        connection.write(cmdWrite(REG_ENABLE), byteArrayOf((if (enable) en or 0x40 else en and 0x40.inv()).toByte()))
    }
}