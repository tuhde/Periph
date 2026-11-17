package it.uhde.periph.chips.power

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/** PMBus DIRECT coefficients (m, b, R). */
class LM25066Coef(val m: Double, val b: Double, val r: Int) {
    /** Convert the raw field [y] to the physical value. */
    fun fromY(y: Int): Double = (y * 10.0.pow(-r) - b) / m

    /** Convert the physical value [x] to the raw field, clamped to 0..0x0FFE. */
    fun toY(x: Double): Int = max(0L, min(0x0FFEL, ((m * x + b) * 10.0.pow(r)).roundToLong())).toInt()
}

/**
 * LM25066 — hot-swap controller / PMBus power monitor (Texas Instruments), minimal driver.
 *
 * Reads the monitored rail: input voltage, output voltage, input current, input power and MOSFET
 * temperature. The constructor performs no bus access and never writes the chip (OPERATION,
 * GATE_MASK and DEVICE_SETUP are left alone). Telemetry is PMBus DIRECT; the current and power
 * coefficients depend on the sense resistor and the CL pin setting.
 *
 * I²C / SMBus addresses: 0x10–0x17, 0x40–0x47, 0x50–0x5A (three-state ADR0/ADR1/ADR2 pins).
 * 16-bit values are little-endian.
 *
 * @param connection I²C / SMBus connection bound to the chip's address
 * @param rSenseMohm sense resistor in milliohms
 * @param currentLimitHigh false = CL pin at GND (25 mV), true = CL pin at VDD (46 mV)
 */
open class LM25066Minimal(
    protected val connection: RegisterConnection,
    protected val rSenseMohm: Double = 1.0,
    currentLimitHigh: Boolean = false
) {
    companion object {
        const val CMD_OPERATION       = 0x01
        const val CMD_CLEAR_FAULTS    = 0x03
        const val CMD_CAPABILITY      = 0x19
        const val CMD_VOUT_UV_WARN    = 0x43
        const val CMD_OT_FAULT        = 0x4F
        const val CMD_OT_WARN         = 0x51
        const val CMD_VIN_OV_WARN     = 0x57
        const val CMD_VIN_UV_WARN     = 0x58
        const val CMD_STATUS_BYTE     = 0x78
        const val CMD_STATUS_WORD     = 0x79
        const val CMD_STATUS_VOUT     = 0x7A
        const val CMD_STATUS_INPUT    = 0x7C
        const val CMD_STATUS_TEMP     = 0x7D
        const val CMD_STATUS_CML      = 0x7E
        const val CMD_STATUS_MFR      = 0x80
        const val CMD_READ_VIN        = 0x88
        const val CMD_READ_VOUT       = 0x8B
        const val CMD_READ_TEMP       = 0x8D
        const val CMD_MFR_ID          = 0x99
        const val CMD_MFR_MODEL       = 0x9A
        const val CMD_MFR_REVISION    = 0x9B
        const val CMD_READ_VAUX       = 0xD0
        const val CMD_READ_IIN        = 0xD1
        const val CMD_READ_PIN        = 0xD2
        const val CMD_IIN_WARN        = 0xD3
        const val CMD_PIN_WARN        = 0xD4
        const val CMD_READ_PIN_PEAK   = 0xD5
        const val CMD_CLEAR_PIN_PEAK  = 0xD6
        const val CMD_GATE_MASK       = 0xD7
        const val CMD_ALERT_MASK      = 0xD8
        const val CMD_DEVICE_SETUP    = 0xD9
        const val CMD_BLOCK_READ      = 0xDA
        const val CMD_SAMPLES_FOR_AVG = 0xDB
        const val CMD_READ_AVG_VIN    = 0xDC
        const val CMD_READ_AVG_VOUT   = 0xDD
        const val CMD_READ_AVG_IIN    = 0xDE
        const val CMD_READ_AVG_PIN    = 0xDF
        const val CMD_BLACK_BOX       = 0xE0
        const val CMD_DIAGNOSTIC      = 0xE1
        const val CMD_AVG_BLOCK_READ  = 0xE2

        val VOLT = LM25066Coef(22070.0, -1800.0, -2)
        val VAUX = LM25066Coef(3546.0, -3.0, 0)
        val TEMP = LM25066Coef(16000.0, 0.0, -3)

        /** Convert a 16-bit temperature word (signed range −256 … +255 °C) to °C. */
        fun tempC(word: Int): Double = word.toShort() / 16.0
    }

    protected var amp: LM25066Coef = LM25066Coef(13661.0 * rSenseMohm, -5200.0, -2)
    protected var watt: LM25066Coef = LM25066Coef(736.0 * rSenseMohm, -3300.0, -2)

    init {
        setCurrentLimit(currentLimitHigh)
    }

    /** Select the current / power coefficients for the CL pin setting. */
    protected fun setCurrentLimit(high: Boolean) {
        if (high) {
            amp = LM25066Coef(6854.0 * rSenseMohm, -3100.0, -2)
            watt = LM25066Coef(369.0 * rSenseMohm, -1900.0, -2)
        } else {
            amp = LM25066Coef(13661.0 * rSenseMohm, -5200.0, -2)
            watt = LM25066Coef(736.0 * rSenseMohm, -3300.0, -2)
        }
    }

    /** Read a little-endian word of command [cmd]. */
    @Throws(IOException::class)
    protected fun readWord(cmd: Int): Int {
        val b = connection.read(cmd, 2)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    /** Write a little-endian word to command [cmd]. */
    @Throws(IOException::class)
    protected fun writeWord(cmd: Int, value: Int) {
        connection.write(cmd, byteArrayOf(value.toByte(), (value shr 8).toByte()))
    }

    /** Read a byte command. */
    @Throws(IOException::class)
    protected fun readByte(cmd: Int): Int = connection.read(cmd, 1)[0].toInt() and 0xFF

    /** Read the 12-bit telemetry field of command [cmd] and convert it with [coef]. */
    @Throws(IOException::class)
    protected fun readConverted(cmd: Int, coef: LM25066Coef): Double = coef.fromY(readWord(cmd) and 0x0FFF)

    /** @return input voltage VIN in V */
    @Throws(IOException::class)
    fun voltageIn(): Double = readConverted(CMD_READ_VIN, VOLT)

    /** @return output voltage VOUT in V */
    @Throws(IOException::class)
    fun voltageOut(): Double = readConverted(CMD_READ_VOUT, VOLT)

    /** @return instantaneous input current in A */
    @Throws(IOException::class)
    fun current(): Double = readConverted(CMD_READ_IIN, amp)

    /** @return instantaneous input power in W */
    @Throws(IOException::class)
    fun power(): Double = readConverted(CMD_READ_PIN, watt)

    /** @return external MOSFET temperature in °C */
    @Throws(IOException::class)
    fun temperature(): Double = tempC(readWord(CMD_READ_TEMP))
}
