package it.uhde.periph.chips.adc_dac

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import kotlin.math.floor

/**
 * AD5252 — full interface: extends [AD5252Minimal] with ratio and W–A helpers,
 * quick commands (±1 step, ±6 dB), EEMEM store/restore/reset, user EEMEM and
 * the factory RAB tolerance. Quick-command channel arguments are `Int?`:
 * `null` addresses both channels. EEMEM writes poll with the NOP quick command
 * until the chip acknowledges again (timeout 100 ms).
 *
 * @param connection configured I²C connection bound to the device (0x2C–0x2F)
 * @param rAb        end-to-end resistance RAB in Ω
 * @param rWiper     wiper resistance RW in Ω
 */
class AD5252Full(
    connection: RegisterConnection,
    rAb: Double = DEFAULT_RAB,
    rWiper: Double = DEFAULT_RW,
) : AD5252Minimal(connection, rAb, rWiper) {

    private companion object {
        const val EEMEM_FLAG = 0x20
        val TOL_INT = intArrayOf(0x3A, 0x3E)

        const val Q_NOP = 0x80
        const val Q_RESTORE = 0x88
        const val Q_STORE = 0x90
        const val Q_HALVE = 0x98
        const val Q_DEC_STEP = 0xA8
        const val Q_INC_6DB = 0xC0
        const val Q_INC_STEP = 0xD0
        const val Q_DEC_6DB_ALL = 0xA0
        const val Q_DEC_STEP_ALL = 0xB0
        const val Q_RESET = 0xB8
        const val Q_INC_6DB_ALL = 0xC8
        const val Q_INC_STEP_ALL = 0xD8
    }

    private fun quickByte(b: Int) {
        connection.write(byteArrayOf(b.toByte()))
    }

    private fun quick(base: Int, baseAll: Int, channel: Int?) {
        if (channel == null) {
            quickByte(baseAll)
        } else {
            checkChannel(channel)
            quickByte(base or RDAC[channel])
        }
    }

    private fun waitReady() {
        repeat(51) {
            try {
                quickByte(Q_NOP)
                return
            } catch (e: IOException) {
                Thread.sleep(2)
            }
        }
        throw IOException("AD5252 busy: EEMEM write did not finish")
    }

    /**
     * Write both wipers in one consecutive-write transaction.
     *
     * @param code0 channel 0 wiper position 0–255
     * @param code1 channel 1 wiper position 0–255
     */
    fun setBoth(code0: Int, code1: Int) {
        connection.write(RDAC[0], byteArrayOf(clampCode(code0).toByte(), clampCode(code1).toByte()))
    }

    /**
     * Set the wiper by divider ratio V(W−B)/V(A−B).
     *
     * @param channel 0 or 1
     * @param ratio   0.0–1.0; the code is `round(ratio × 256)` clamped to 255
     */
    fun setRatio(channel: Int, ratio: Double) {
        writeCode(channel, floor(ratio * 256.0 + 0.5).toInt())
    }

    /**
     * Read the divider ratio of a channel.
     *
     * @param channel 0 or 1
     * @return `code / 256`
     */
    fun getRatio(channel: Int): Double = readCode(channel) / 256.0

    /**
     * Set the W–A resistance (rheostat mode, B open) to the nearest code.
     *
     * @param channel 0 or 1
     * @param ohms    target W–A resistance in Ω
     */
    fun setResistanceWa(channel: Int, ohms: Double) {
        val steps = floor((ohms - rW) / rAb * 256.0 + 0.5).toInt()
        writeCode(channel, 256 - steps)
    }

    /**
     * Read the W–A resistance for the current wiper code.
     *
     * @param channel 0 or 1
     * @return resistance in Ω, `(256 − D)/256 · RAB + RW`
     */
    fun getResistanceWa(channel: Int): Double = (256 - readCode(channel)) / 256.0 * rAb + rW

    /**
     * Increment the wiper by one step (quick command).
     *
     * @param channel 0, 1 or `null` for both channels
     */
    fun increment(channel: Int? = null) = quick(Q_INC_STEP, Q_INC_STEP_ALL, channel)

    /**
     * Decrement the wiper by one step (quick command).
     *
     * @param channel 0, 1 or `null` for both channels
     */
    fun decrement(channel: Int? = null) = quick(Q_DEC_STEP, Q_DEC_STEP_ALL, channel)

    /**
     * Increase the level by 6 dB — the code doubles (quick command).
     *
     * @param channel 0, 1 or `null` for both channels
     */
    fun doubleCode(channel: Int? = null) = quick(Q_INC_6DB, Q_INC_6DB_ALL, channel)

    /**
     * Decrease the level by 6 dB — the code halves (quick command).
     *
     * @param channel 0, 1 or `null` for both channels
     */
    fun halve(channel: Int? = null) = quick(Q_HALVE, Q_DEC_6DB_ALL, channel)

    /**
     * Store the wiper in EEMEM (restored at power-on) and wait for the ≈ 26 ms
     * write cycle by acknowledge polling.
     *
     * @param channel 0, 1 or `null` for both channels
     * @throws IOException on bus error or if the chip does not acknowledge within 100 ms
     */
    fun store(channel: Int? = null) {
        for (ch in if (channel == null) listOf(0, 1) else listOf(channel)) {
            quick(Q_STORE, 0, ch)
            waitReady()
        }
    }

    /**
     * Restore the wiper from EEMEM, then send a NOP to return to idle.
     *
     * @param channel 0, 1 or `null` for both channels
     */
    fun restore(channel: Int? = null) {
        for (ch in if (channel == null) listOf(0, 1) else listOf(channel)) {
            quick(Q_RESTORE, 0, ch)
        }
        nop()
    }

    /**
     * Quick-command reset (`0xB8`): restore both wipers from EEMEM and wait
     * for the chip to acknowledge again.
     *
     * @throws IOException if the chip does not acknowledge within 100 ms
     */
    fun reset() {
        quickByte(Q_RESET)
        waitReady()
    }

    /**
     * Read a user EEMEM byte.
     *
     * @param index 4–15
     * @return the stored byte 0–255
     * @throws IllegalArgumentException if index is outside 4–15
     */
    fun readEemem(index: Int): Int {
        checkIndex(index)
        return connection.read(EEMEM_FLAG or index, 1)[0].toInt() and 0xFF
    }

    /**
     * Write a user EEMEM byte and wait for the ≈ 26 ms write cycle.
     *
     * @param index 4–15
     * @param value byte 0–255
     * @throws IllegalArgumentException if index is outside 4–15
     * @throws IOException if the chip does not acknowledge within 100 ms
     */
    fun writeEemem(index: Int, value: Int) {
        checkIndex(index)
        connection.write(EEMEM_FLAG or index, byteArrayOf(value.toByte()))
        waitReady()
    }

    private fun checkIndex(index: Int) {
        require(index in 4..15) { "index must be 4..15" }
    }

    /**
     * Read the factory-stored RAB tolerance of a channel.
     *
     * @param channel 0 or 1
     * @return tolerance in percent (sign-magnitude integer byte plus decimal byte / 256)
     */
    fun readTolerance(channel: Int): Double {
        checkChannel(channel)
        val raw = connection.read(TOL_INT[channel], 2)
        val hi = raw[0].toInt() and 0xFF
        val value = (hi and 0x7F) + (raw[1].toInt() and 0xFF) / 256.0
        return if (hi and 0x80 != 0) -value else value
    }

    /**
     * Actual RAB of a channel from its stored tolerance.
     *
     * @param channel 0 or 1
     * @return `rAb × (1 + tolerance / 100)` in Ω
     */
    fun rabActual(channel: Int): Double = rAb * (1.0 + readTolerance(channel) / 100.0)

    /** Send the NOP quick command (`0x80`), returning the chip to idle. */
    fun nop() = quickByte(Q_NOP)

    /**
     * Change the resistance parameters used by conversions (no bus traffic).
     *
     * @param rAb    end-to-end resistance RAB in Ω
     * @param rWiper wiper resistance RW in Ω; null keeps the current value
     */
    fun setRab(rAb: Double, rWiper: Double? = null) {
        this.rAb = rAb
        if (rWiper != null) this.rW = rWiper
    }
}
