package it.uhde.periph.chips.power

import it.uhde.periph.connection.EdgeHandler
import it.uhde.periph.connection.EdgeTrigger
import it.uhde.periph.connection.InputPin
import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import java.util.function.IntConsumer

/**
 * LM25066 — full driver. Extends [LM25066Minimal] with averaging, warning limits, status, MOSFET
 * control, device setup, black box and alerts.
 *
 * **WARNING:** [operationOff] cuts the load rail and [setGateMask] can stop hardware faults from
 * switching the MOSFET off (which may destroy it). Neither is ever called by the constructor.
 *
 * `SOURCE_*` constants are ALERT_MASK bit positions (1 = masked) for [enableInterrupt] /
 * [disableInterrupt]. [onInterrupt] uses `connection.intPin()` if wired (falling edge on SMBA),
 * otherwise a 1 s polling thread.
 *
 * @param connection I²C / SMBus connection bound to the chip's address
 * @param rSenseMohm sense resistor in milliohms
 * @param currentLimitHigh false = CL pin at GND (25 mV), true = CL pin at VDD (46 mV)
 */
class LM25066Full(
    connection: RegisterConnection,
    rSenseMohm: Double = 1.0,
    currentLimitHigh: Boolean = false
) : LM25066Minimal(connection, rSenseMohm, currentLimitHigh) {

    /** Converted BLOCK_READ / AVG_BLOCK_READ / BLACK_BOX_READ telemetry. */
    data class Block(
        val diagnostic: Int,
        val current: Double,
        val voltageOut: Double,
        val voltageIn: Double,
        val power: Double,
        val temperature: Double
    )

    /** All warning / fault thresholds; `null` where disabled. */
    data class Limits(
        val vinOvWarn: Double?,
        val vinUvWarn: Double?,
        val voutUvWarn: Double?,
        val otWarn: Double?,
        val otFault: Double?,
        val iinWarn: Double?,
        val pinWarn: Double?
    )

    companion object {
        /** VOUT under-voltage warning. */
        const val SOURCE_VOUT_UV_WARN = 0x8000
        /** Input current limit warning. */
        const val SOURCE_IIN_WARN = 0x4000
        /** VIN under-voltage warning. */
        const val SOURCE_VIN_UV_WARN = 0x2000
        /** VIN over-voltage warning. */
        const val SOURCE_VIN_OV_WARN = 0x1000
        /** Power Good negated (masked by default). */
        const val SOURCE_POWER_GOOD = 0x0800
        /** Over-temperature warning. */
        const val SOURCE_OT_WARN = 0x0400
        /** Over-power limit warning. */
        const val SOURCE_PIN_OP_WARN = 0x0100
        /** External MOSFET shorted. */
        const val SOURCE_MOSFET_SHORTED = 0x0040
        /** VIN under-voltage fault (masked by default). */
        const val SOURCE_VIN_UV_FAULT = 0x0020
        /** VIN over-voltage fault. */
        const val SOURCE_VIN_OV_FAULT = 0x0010
        /** IIN / PFET over-current fault. */
        const val SOURCE_IIN_FAULT = 0x0008
        /** Over-temperature fault. */
        const val SOURCE_OT_FAULT = 0x0004
        /** Communication fault. */
        const val SOURCE_CML_FAULT = 0x0002
        /** Circuit-breaker fault. */
        const val SOURCE_CB_FAULT = 0x0001
    }

    @Volatile private var callback: IntConsumer? = null
    private var subscribedPin: InputPin? = null
    @Volatile private var polling = false
    private var pollThread: Thread? = null
    private val edgeHandler = EdgeHandler { handleEdge() }

    @Throws(IOException::class)
    private fun block(cmd: Int): Block {
        val raw = connection.read(cmd, 13)
        if ((raw[0].toInt() and 0xFF) != 12) throw IOException("LM25066 block read count ${raw[0].toInt() and 0xFF} != 12")
        val w = IntArray(6) { (raw[1 + 2 * it].toInt() and 0xFF) or ((raw[2 + 2 * it].toInt() and 0xFF) shl 8) }
        return Block(
            w[0], amp.fromY(w[1] and 0x0FFF), VOLT.fromY(w[2] and 0x0FFF), VOLT.fromY(w[3] and 0x0FFF),
            watt.fromY(w[4] and 0x0FFF), tempC(w[5])
        )
    }

    @Throws(IOException::class)
    private fun text(cmd: Int, n: Int): String {
        val raw = connection.read(cmd, n + 1)
        if ((raw[0].toInt() and 0xFF) != n) throw IOException("LM25066 block read count ${raw[0].toInt() and 0xFF} != $n")
        val sb = StringBuilder()
        for (i in 1..n) if (raw[i].toInt() != 0) sb.append((raw[i].toInt() and 0xFF).toChar())
        return sb.toString()
    }

    /** @return voltage at the VAUX pin in V (0 … 1.16 V) */
    @Throws(IOException::class)
    fun voltageAux(): Double = readConverted(CMD_READ_VAUX, VAUX)

    /** @return BLOCK_READ: time-aligned telemetry */
    @Throws(IOException::class)
    fun readBlock(): Block = block(CMD_BLOCK_READ)

    /** @return AVG_BLOCK_READ: averaged telemetry */
    @Throws(IOException::class)
    fun readAverage(): Block = block(CMD_AVG_BLOCK_READ)

    /** @return BLACK_BOX_READ: the snapshot latched at the first SMBA since [clearFaults] */
    @Throws(IOException::class)
    fun blackBox(): Block = block(CMD_BLACK_BOX)

    /** @return averaged VIN in V */
    @Throws(IOException::class)
    fun voltageInAvg(): Double = readConverted(CMD_READ_AVG_VIN, VOLT)

    /** @return averaged VOUT in V */
    @Throws(IOException::class)
    fun voltageOutAvg(): Double = readConverted(CMD_READ_AVG_VOUT, VOLT)

    /** @return averaged input current in A */
    @Throws(IOException::class)
    fun currentAvg(): Double = readConverted(CMD_READ_AVG_IIN, amp)

    /** @return averaged input power in W */
    @Throws(IOException::class)
    fun powerAvg(): Double = readConverted(CMD_READ_AVG_PIN, watt)

    /** @return peak input power in W since reset or [clearPowerPeak] */
    @Throws(IOException::class)
    fun powerPeak(): Double = readConverted(CMD_READ_PIN_PEAK, watt)

    /** Reset the peak-power register (CLEAR_PIN_PEAK, send byte). */
    @Throws(IOException::class)
    fun clearPowerPeak() {
        connection.write(CMD_CLEAR_PIN_PEAK, ByteArray(0))
    }

    /**
     * Average over 2^[exponent] samples.
     *
     * @param exponent AVGN, 0–12
     * @throws IllegalArgumentException if exponent is outside 0–12
     */
    @Throws(IOException::class)
    fun setAveraging(exponent: Int) {
        require(exponent in 0..12) { "exponent must be 0-12" }
        connection.write(CMD_SAMPLES_FOR_AVG, byteArrayOf(exponent.toByte()))
    }

    /** @return SAMPLES_FOR_AVG (the exponent) */
    @Throws(IOException::class)
    fun getAveraging(): Int = readByte(CMD_SAMPLES_FOR_AVG)

    @Throws(IOException::class)
    private fun setLimit(cmd: Int, value: Double?, coef: LM25066Coef, disabled: Int) {
        writeWord(cmd, if (value == null) disabled else coef.toY(value))
    }

    @Throws(IOException::class)
    private fun getLimit(cmd: Int, coef: LM25066Coef, disabled: Int): Double? {
        val y = readWord(cmd) and 0x0FFF
        return if (y == disabled) null else coef.fromY(y)
    }

    /** VIN over-voltage warning in V; `null` disables. */
    @Throws(IOException::class)
    fun setVinOvWarn(volts: Double?) = setLimit(CMD_VIN_OV_WARN, volts, VOLT, 0x0FFF)

    /** VIN under-voltage warning in V; `null` disables. */
    @Throws(IOException::class)
    fun setVinUvWarn(volts: Double?) = setLimit(CMD_VIN_UV_WARN, volts, VOLT, 0x0000)

    /** VOUT under-voltage warning in V; `null` disables. */
    @Throws(IOException::class)
    fun setVoutUvWarn(volts: Double?) = setLimit(CMD_VOUT_UV_WARN, volts, VOLT, 0x0000)

    /** Over-temperature warning in °C; `null` disables. */
    @Throws(IOException::class)
    fun setOtWarn(celsius: Double?) = setLimit(CMD_OT_WARN, celsius, TEMP, 0x0FFF)

    /** Over-temperature fault in °C; `null` disables. */
    @Throws(IOException::class)
    fun setOtFault(celsius: Double?) = setLimit(CMD_OT_FAULT, celsius, TEMP, 0x0FFF)

    /** Input over-current warning in A; `null` disables. */
    @Throws(IOException::class)
    fun setIinWarn(amps: Double?) = setLimit(CMD_IIN_WARN, amps, amp, 0x0FFF)

    /** Input over-power warning in W; `null` disables. */
    @Throws(IOException::class)
    fun setPinWarn(watts: Double?) = setLimit(CMD_PIN_WARN, watts, watt, 0x0FFF)

    /** @return all warning / fault thresholds (`null` where disabled) */
    @Throws(IOException::class)
    fun getLimits(): Limits = Limits(
        getLimit(CMD_VIN_OV_WARN, VOLT, 0x0FFF), getLimit(CMD_VIN_UV_WARN, VOLT, 0x0000),
        getLimit(CMD_VOUT_UV_WARN, VOLT, 0x0000), getLimit(CMD_OT_WARN, TEMP, 0x0FFF),
        getLimit(CMD_OT_FAULT, TEMP, 0x0FFF), getLimit(CMD_IIN_WARN, amp, 0x0FFF),
        getLimit(CMD_PIN_WARN, watt, 0x0FFF)
    )

    /** @return STATUS_BYTE */
    @Throws(IOException::class)
    fun statusByte(): Int = readByte(CMD_STATUS_BYTE)

    /** @return STATUS_WORD */
    @Throws(IOException::class)
    fun statusWord(): Int = readWord(CMD_STATUS_WORD)

    /** @return STATUS_VOUT */
    @Throws(IOException::class)
    fun statusVout(): Int = readByte(CMD_STATUS_VOUT)

    /** @return STATUS_INPUT */
    @Throws(IOException::class)
    fun statusInput(): Int = readByte(CMD_STATUS_INPUT)

    /** @return STATUS_TEMPERATURE */
    @Throws(IOException::class)
    fun statusTemperature(): Int = readByte(CMD_STATUS_TEMP)

    /** @return STATUS_CML */
    @Throws(IOException::class)
    fun statusCml(): Int = readByte(CMD_STATUS_CML)

    /** @return STATUS_MFR_SPECIFIC */
    @Throws(IOException::class)
    fun statusMfr(): Int = readByte(CMD_STATUS_MFR)

    /** @return READ_DIAGNOSTIC_WORD (all faults and warnings) */
    @Throws(IOException::class)
    fun diagnosticWord(): Int = readWord(CMD_DIAGNOSTIC)

    /** CLEAR_FAULTS (send byte); does not switch the MOSFET on. */
    @Throws(IOException::class)
    fun clearFaults() {
        connection.write(CMD_CLEAR_FAULTS, ByteArray(0))
    }

    /** OPERATION = 0x80: switches the load on. */
    @Throws(IOException::class)
    fun operationOn() {
        connection.write(CMD_OPERATION, byteArrayOf(0x80.toByte()))
    }

    /** OPERATION = 0x00: CUTS THE LOAD RAIL. */
    @Throws(IOException::class)
    fun operationOff() {
        connection.write(CMD_OPERATION, byteArrayOf(0x00))
    }

    /** @return true if OPERATION reads 0x80 and the STATUS_BYTE OFF bit is clear */
    @Throws(IOException::class)
    fun isOn(): Boolean = readByte(CMD_OPERATION) == 0x80 && (readByte(CMD_STATUS_BYTE) and 0x40) == 0

    /** @return DEVICE_SETUP */
    @Throws(IOException::class)
    fun deviceSetup(): Int = readByte(CMD_DEVICE_SETUP)

    /**
     * Override the RETRY / CL / CB pin settings from software; only the non-null arguments are
     * overridden. Updates the driver coefficients when [currentLimitHigh] is given.
     *
     * @param retryCode 0–7 (bits 7:5: 0 pin, 1 none, 2 = 1 retry, 3 = 2, 4 = 4, 5 = 8, 6 = 16, 7 = unlimited), or null
     * @param currentLimitHigh true = 46 mV, false = 25 mV, or null
     * @param cbRatioHigh true = 3.6×, false = 1.8×, or null
     * @throws IllegalArgumentException if retryCode is outside 0–7
     */
    @Throws(IOException::class)
    fun configureDevice(retryCode: Int? = null, currentLimitHigh: Boolean? = null, cbRatioHigh: Boolean? = null) {
        var value = 0
        if (retryCode != null) {
            require(retryCode in 0..7) { "retryCode must be 0-7" }
            value = value or (retryCode shl 5)
        }
        if (currentLimitHigh != null) value = value or 0x04 or (if (currentLimitHigh) 0x10 else 0)
        if (cbRatioHigh != null) value = value or 0x02 or (if (cbRatioHigh) 0x08 else 0)
        connection.write(CMD_DEVICE_SETUP, byteArrayOf(value.toByte()))
        if (currentLimitHigh != null) setCurrentLimit(currentLimitHigh)
    }

    /**
     * Write GATE_MASK. DANGEROUS: can disable fault shut-off of the MOSFET.
     *
     * @param mask bits 5, 4, 2 only
     * @throws IllegalArgumentException if other bits are set
     */
    @Throws(IOException::class)
    fun setGateMask(mask: Int) {
        require(mask and 0x34.inv() == 0) { "only GATE_MASK bits 5, 4, 2 may be set" }
        connection.write(CMD_GATE_MASK, byteArrayOf(mask.toByte()))
    }

    /** @return GATE_MASK */
    @Throws(IOException::class)
    fun getGateMask(): Int = readByte(CMD_GATE_MASK)

    /** @return CAPABILITY */
    @Throws(IOException::class)
    fun capability(): Int = readByte(CMD_CAPABILITY)

    /** @return MFR_ID as a string */
    @Throws(IOException::class)
    fun manufacturerId(): String = text(CMD_MFR_ID, 3)

    /** @return MFR_MODEL as a string */
    @Throws(IOException::class)
    fun model(): String = text(CMD_MFR_MODEL, 8)

    /** @return MFR_REVISION as a string */
    @Throws(IOException::class)
    fun revision(): String = text(CMD_MFR_REVISION, 2)

    /** Unmask alert source bit(s) in ALERT_MASK (read-modify-write); use the `SOURCE_*` constants. */
    @Throws(IOException::class)
    fun enableInterrupt(source: Int) {
        writeWord(CMD_ALERT_MASK, readWord(CMD_ALERT_MASK) and source.inv() and 0xFFFF)
    }

    /** Mask alert source bit(s) in ALERT_MASK (read-modify-write); use the `SOURCE_*` constants. */
    @Throws(IOException::class)
    fun disableInterrupt(source: Int) {
        writeWord(CMD_ALERT_MASK, readWord(CMD_ALERT_MASK) or source)
    }

    /**
     * Subscribe to SMBA (falling edge).
     *
     * @param callback receives READ_DIAGNOSTIC_WORD
     * @param intPin pin to arm, or `null` to force the 1 s polling fallback; defaults to `connection.intPin()`
     */
    fun onInterrupt(callback: IntConsumer, intPin: InputPin? = connection.intPin()) {
        offInterrupt()
        this.callback = callback
        if (intPin != null) {
            subscribedPin = intPin
            intPin.onEdge(edgeHandler, EdgeTrigger.FALLING)
        } else {
            polling = true
            pollThread = Thread({
                while (polling) {
                    handleEdge()
                    try {
                        Thread.sleep(1000)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@Thread
                    }
                }
            }, "lm25066-poll").also { it.isDaemon = true; it.start() }
        }
    }

    /** Unsubscribe and stop delivery. */
    fun offInterrupt() {
        callback = null
        subscribedPin?.offEdge(edgeHandler)
        subscribedPin = null
        polling = false
        pollThread?.interrupt()
        pollThread = null
    }

    /** @return READ_DIAGNOSTIC_WORD (does not clear; call [clearFaults] after servicing) */
    @Throws(IOException::class)
    fun pollInterrupt(): Int = readWord(CMD_DIAGNOSTIC)

    private fun handleEdge() {
        val cb = callback ?: return
        try {
            val status = pollInterrupt()
            if (status != 0) cb.accept(status)
        } catch (e: IOException) {
            // transient bus error: the next edge or tick retries
        }
    }
}
