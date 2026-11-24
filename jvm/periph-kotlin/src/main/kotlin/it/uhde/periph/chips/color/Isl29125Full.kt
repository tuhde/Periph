package it.uhde.periph.chips.color

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * ISL29125 full interface — extends [Isl29125Minimal] with single-channel
 * reads, mode, range, resolution, synchronisation and IR-compensation
 * configuration, thresholds, persistence, and interrupt delivery.
 *
 * Setters with a restricted value throw [IllegalArgumentException] and write
 * nothing for an unsupported argument.
 *
 * @param connection I²C register connection bound to the device (0x44)
 */
class Isl29125Full(connection: RegisterConnection) : Isl29125Minimal(connection) {

    companion object {
        /** Interrupt source: selected channel outside the threshold window. */
        const val SOURCE_THRESHOLD = 0x01

        /** Interrupt source: conversion complete. */
        const val SOURCE_CONVERSION = 0x02

        /** Mode: power-down. */
        const val MODE_POWER_DOWN = 0

        /** Mode: green only. */
        const val MODE_GREEN = 1

        /** Mode: red only. */
        const val MODE_RED = 2

        /** Mode: blue only. */
        const val MODE_BLUE = 3

        /** Mode: standby (no conversion). */
        const val MODE_STANDBY = 4

        /** Mode: green, red and blue. */
        const val MODE_RGB = 5

        /** Mode: green and red. */
        const val MODE_GREEN_RED = 6

        /** Mode: green and blue. */
        const val MODE_GREEN_BLUE = 7

        /** Interrupt channel: none. */
        const val CHANNEL_NONE = 0

        /** Interrupt channel: green. */
        const val CHANNEL_GREEN = 1

        /** Interrupt channel: red. */
        const val CHANNEL_RED = 2

        /** Interrupt channel: blue. */
        const val CHANNEL_BLUE = 3

        private val PERSISTENCE = intArrayOf(1, 2, 4, 8)
    }

    @Volatile
    private var callback: ((Int) -> Unit)? = null
    private var intPinUsed: InputPin? = null
    private var pollThread: Thread? = null

    @Volatile
    private var polling = false

    /** Fixed edge handler so offEdge() can remove the exact instance that was registered. */
    private val edgeHandler = EdgeHandler { handleEdge() }

    /** @return red count (2-byte read) */
    fun readRed(): Int = readChannel(REG_RED)

    /** @return green count */
    fun readGreen(): Int = readChannel(REG_GREEN)

    /** @return blue count */
    fun readBlue(): Int = readChannel(REG_BLUE)

    /** Block-read all three channels without waiting. */
    fun readRgb(): Counts = readBlock()

    /**
     * Report whether a conversion has completed. Reads STATUS, which clears its
     * flags; the flags are kept for [pollInterrupt] and [status].
     *
     * @return true if CONVENF was set
     */
    fun dataReady(): Boolean = (readStatus() and STATUS_CONVERSION) != 0

    /**
     * Select the operating mode (conversions are continuous).
     *
     * @param mode [MODE_POWER_DOWN] … [MODE_GREEN_BLUE] (0–7)
     * @throws IllegalArgumentException if outside 0–7
     */
    fun setMode(mode: Int) {
        require(mode in MODE_POWER_DOWN..MODE_GREEN_BLUE) { "mode must be 0-7" }
        updateReg(REG_CONFIG1, mode, CFG1_MODE)
    }

    /**
     * Select the sensing range.
     *
     * @param lux 375 or 10000 (full-scale illuminance)
     * @throws IllegalArgumentException for any other value
     */
    fun setRange(lux: Int) {
        when (lux) {
            375 -> updateReg(REG_CONFIG1, 0, CFG1_RNG_10K)
            10000 -> updateReg(REG_CONFIG1, CFG1_RNG_10K, 0)
            else -> throw IllegalArgumentException("range must be 375 or 10000")
        }
    }

    /**
     * Select the ADC resolution.
     *
     * @param bits 12 or 16
     * @throws IllegalArgumentException for any other value
     */
    fun setResolution(bits: Int) {
        when (bits) {
            16 -> updateReg(REG_CONFIG1, 0, CFG1_BITS_12)
            12 -> updateReg(REG_CONFIG1, CFG1_BITS_12, 0)
            else -> throw IllegalArgumentException("resolution must be 12 or 16")
        }
    }

    /**
     * Select the conversion start source.
     *
     * @param external true: the rising edge of INT starts the conversion (INT becomes an input); false: the I²C write
     */
    fun setSync(external: Boolean) = updateReg(REG_CONFIG1, if (external) CFG1_SYNC else 0, CFG1_SYNC)

    /**
     * Set the active IR compensation (CONFIG2).
     *
     * @param offset false: codes 0–63; true: codes 106–169
     * @param adjust compensation adjust, 0–63
     * @throws IllegalArgumentException if [adjust] is outside 0–63
     */
    fun setIrCompensation(offset: Boolean, adjust: Int) {
        require(adjust in 0..63) { "adjust must be 0-63" }
        writeReg(REG_CONFIG2, (if (offset) 0x80 else 0) or adjust)
    }

    /** @return DEVICE_ID (0x7D for the ISL29125) */
    fun deviceId(): Int = readReg(REG_DEVICE_ID)

    /**
     * Read STATUS (clearing the flags on the chip).
     *
     * @return raw status byte with the flags seen since the last call merged in
     */
    fun status(): Int {
        val value = readStatus() or pending
        pending = 0
        return value
    }

    /** Reset all registers to their defaults (writes 0x46 to register 0x00). */
    fun softReset() {
        writeReg(REG_DEVICE_ID, RESET_VALUE)
        sleepMs(if (pollStepMs > 0) 10 else 0)
        pending = 0
    }

    /**
     * Set the 16-bit threshold window.
     *
     * @param low lower threshold (0–65535)
     * @param high upper threshold (0–65535)
     * @throws IllegalArgumentException if a value is outside 16 bit or `low > high`
     */
    fun setThresholds(low: Int, high: Int) {
        require(low in 0..0xFFFF && high in 0..0xFFFF && low <= high) {
            "thresholds must satisfy 0 <= low <= high <= 65535"
        }
        connection.write(REG_LOW_TH, byteArrayOf(low.toByte(), (low shr 8).toByte()))
        connection.write(REG_HIGH_TH, byteArrayOf(high.toByte(), (high shr 8).toByte()))
    }

    /** @return the threshold window as `(low, high)` */
    fun getThresholds(): Pair<Int, Int> =
        Pair(word(connection.read(REG_LOW_TH, 2), 0), word(connection.read(REG_HIGH_TH, 2), 0))

    /**
     * Select the channel the threshold interrupt monitors.
     *
     * @param channel [CHANNEL_NONE], [CHANNEL_GREEN], [CHANNEL_RED] or [CHANNEL_BLUE]
     * @throws IllegalArgumentException for an unknown channel
     */
    fun setInterruptChannel(channel: Int) {
        require(channel in CHANNEL_NONE..CHANNEL_BLUE) { "channel must be 0-3" }
        updateReg(REG_CONFIG3, channel, 0x03)
    }

    /**
     * Set the number of integration cycles before the interrupt asserts.
     *
     * @param count 1, 2, 4 or 8
     * @throws IllegalArgumentException for any other value
     */
    fun setPersistence(count: Int) {
        val code = PERSISTENCE.indexOf(count)
        require(code >= 0) { "count must be 1, 2, 4 or 8" }
        updateReg(REG_CONFIG3, code shl 2, 0x0C)
    }

    /**
     * Enable an interrupt source. For [SOURCE_THRESHOLD] select the channel
     * beforehand; green is used if none is set.
     *
     * @param source [SOURCE_THRESHOLD] or [SOURCE_CONVERSION]
     */
    fun enableInterrupt(source: Int = SOURCE_THRESHOLD) {
        if (source == SOURCE_CONVERSION) {
            updateReg(REG_CONFIG3, CFG3_CONVEN, 0)
        } else if ((readReg(REG_CONFIG3) and 0x03) == 0) {
            updateReg(REG_CONFIG3, CHANNEL_GREEN, 0)
        }
    }

    /**
     * Disable an interrupt source (threshold: INTSEL = none; conversion: CONVEN = 0).
     *
     * @param source [SOURCE_THRESHOLD] or [SOURCE_CONVERSION]
     */
    fun disableInterrupt(source: Int = SOURCE_THRESHOLD) {
        if (source == SOURCE_CONVERSION) updateReg(REG_CONFIG3, 0, CFG3_CONVEN)
        else updateReg(REG_CONFIG3, 0, 0x03)
    }

    /**
     * Read STATUS (clearing the flags) and return it; same as [status].
     *
     * @return status byte; [STATUS_THRESHOLD] / [STATUS_CONVERSION] are set for events seen
     */
    fun pollInterrupt(): Int = status()

    /**
     * Subscribe to INT assertions (falling edge). Falls back to a 50 ms polling
     * thread if no [InputPin] is available.
     *
     * @param intPin overrides `connection.intPin()`, or null
     * @param callback called with the STATUS byte after it has been read and cleared
     */
    @Synchronized
    fun onInterrupt(intPin: InputPin? = null, callback: (Int) -> Unit) {
        this.callback = callback
        startDelivery(intPin ?: connection.intPin())
    }

    private fun startDelivery(pin: InputPin?) {
        stopDelivery()
        intPinUsed = pin
        if (pin != null) {
            pin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = Thread({
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(50)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }, "isl29125-poll").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    private fun stopDelivery() {
        intPinUsed?.offEdge(edgeHandler)
        intPinUsed = null
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    /** Unsubscribe and stop delivery. */
    @Synchronized
    fun offInterrupt() {
        stopDelivery()
        callback = null
    }

    private fun handleEdge() {
        try {
            val status = pollInterrupt()
            if ((status and (STATUS_THRESHOLD or STATUS_CONVERSION)) != 0) callback?.invoke(status)
        } catch (ignored: IOException) {
            // bus error; wait for the next edge/tick rather than propagating
        }
    }
}
