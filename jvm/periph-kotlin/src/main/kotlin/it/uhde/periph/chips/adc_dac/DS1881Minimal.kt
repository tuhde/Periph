package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * DS1881 — dual nonvolatile audio log-taper digital potentiometer (Maxim) —
 * minimal interface.
 *
 * Two 45 kΩ potentiometers whose wiper positions are steps in an attenuation
 * table. Option 1 gives 64 positions (1 dB per step, 0 to −62 dB, position 63
 * = mute); Option 2 (DS1808 compatible) gives 34 positions (12 × 1 dB,
 * 12 × 2 dB, 8 × 3 dB, position 33 = mute). The I²C address is 0x28
 * (A2 = A1 = A0 = 0) to 0x2F.
 *
 * The chip has no register map: a write is one or more command bytes
 * (`00pppppp` wiper 0, `01pppppp` wiper 1, `10xxxVZC` configuration) and a
 * read returns wiper 0, wiper 1 and the configuration register round-robin.
 * The driver therefore uses the plain [Connection] `write` / `read` calls.
 * Wipers and configuration live in EEPROM when V/NV = 0 (50 000 writes); the
 * drivers default to volatile operation. The CE pin is active low and is left
 * to the Connection's enable pin.
 *
 * Defaults: Option 1, volatile wipers (both channels at mute after power-up),
 * zero-crossing detection on. Construction performs no bus traffic; call
 * [init] once after power-up.
 *
 * @param connection configured I²C connection bound to the device (0x28–0x2F)
 */
open class DS1881Minimal(protected val connection: Connection) {

    companion object {
        /** 7-bit I²C address with A2 = A1 = A0 = 0 (0x28–0x2F with the address pins). */
        const val DEFAULT_ADDRESS = 0x28

        const val CMD_WIPER1 = 0x40
        const val CMD_CONFIG = 0x80
        const val CFG_VOLATILE = 0x04
        const val CFG_ZC = 0x02
        const val CFG_OPTION2 = 0x01
        const val CFG_MASK = 0x07

        /** Volatile, zero-crossing on, Option 1. */
        const val CFG_DEFAULT = CFG_VOLATILE or CFG_ZC
        const val MUTE_DB = 80.0

        internal fun sleepMs(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    protected var cfgBits: Int = CFG_DEFAULT
    protected var optionNo: Int = 1

    /**
     * Run the initialization sequence: wait the 1 ms power-up time, read the
     * three bytes and rewrite the configuration (volatile, zero-crossing on,
     * Option 1) only if it differs, waiting for the EEPROM write that this
     * triggers. The wipers are not touched.
     */
    fun init() {
        sleepMs(1)
        val raw = connection.read(3)
        if ((raw[2].toInt() and CFG_MASK) != CFG_DEFAULT) {
            connection.write(byteArrayOf((CMD_CONFIG or CFG_DEFAULT).toByte()))
            doWaitReady(0.05)
        }
        cfgBits = CFG_DEFAULT
        optionNo = 1
    }

    /** Acknowledge-poll with a one-byte read until the device answers or the timeout passes. */
    protected fun doWaitReady(timeoutS: Double) {
        val attempts = (timeoutS / 0.001).toInt() + 1
        var last: IOException? = null
        for (i in 0 until attempts) {
            try {
                connection.read(1)
                return
            } catch (e: IOException) {
                last = e
                sleepMs(1)
            }
        }
        throw IOException("DS1881 busy: EEPROM write did not finish", last)
    }

    protected fun writeCommands(data: ByteArray) {
        connection.write(data)
        if ((cfgBits and CFG_VOLATILE) == 0) doWaitReady(0.05)
    }

    protected fun checkChannel(channel: Int) {
        require(channel == 0 || channel == 1) { "channel must be 0 or 1" }
    }

    protected fun mutePosition(): Int = if (optionNo == 1) 63 else 33

    protected fun positionToDbImpl(position: Int): Double {
        if (position >= mutePosition()) return MUTE_DB
        if (optionNo == 1 || position <= 12) return position.toDouble()
        if (position <= 24) return (12 + 2 * (position - 12)).toDouble()
        return (36 + 3 * (position - 24)).toDouble()
    }

    /** Nearest non-mute position for [db], clamped to the table. */
    protected fun nearestPosition(db: Double): Int {
        if (db <= 0) return 0
        if (optionNo == 1) return minOf((db + 0.5).toInt(), 62)
        var best = 0
        var bestErr = db
        for (p in 1..32) {
            val err = Math.abs(positionToDbImpl(p) - db)
            if (err < bestErr) {
                best = p
                bestErr = err
            }
        }
        return best
    }

    /** Position for an attenuation; Full overrides this to map ≥ 70 dB to mute. */
    protected open fun dbToPos(db: Double): Int = nearestPosition(db)

    protected fun writePosition(channel: Int, position: Int) {
        checkChannel(channel)
        val value = maxOf(0, minOf(position, mutePosition()))
        writeCommands(byteArrayOf(((channel shl 6) or value).toByte()))
    }

    protected fun readAllRaw(): IntArray {
        val raw = connection.read(3)
        return intArrayOf(raw[0].toInt() and 0x3F, raw[1].toInt() and 0x3F, raw[2].toInt() and 0xFF)
    }

    /**
     * Set a channel's attenuation to the nearest position (rounded to the
     * nearest step, clamped to 0…62 dB in Option 1).
     *
     * @param channel 0 or 1
     * @param db attenuation in dB (positive = quieter)
     */
    fun setAttenuation(channel: Int, db: Double) {
        checkChannel(channel)
        writePosition(channel, dbToPos(db))
    }

    /**
     * Read a channel's attenuation.
     *
     * @param channel 0 or 1
     * @return attenuation in dB (`80.0` for mute)
     */
    fun getAttenuation(channel: Int): Double = positionToDbImpl(getPosition(channel))

    /**
     * Mute a channel (position 63 in Option 1).
     *
     * @param channel 0 or 1
     */
    fun mute(channel: Int) {
        writePosition(channel, mutePosition())
    }

    /**
     * Write a raw wiper position.
     *
     * @param channel 0 or 1
     * @param position 0–63 (63 = mute); clamped
     */
    fun setPosition(channel: Int, position: Int) {
        writePosition(channel, position)
    }

    /**
     * Read a wiper position with the three-byte round-robin read.
     *
     * @param channel 0 or 1
     * @return position (low 6 bits of the read-back byte)
     */
    fun getPosition(channel: Int): Int {
        checkChannel(channel)
        return readAllRaw()[channel]
    }
}
