package it.uhde.periph.chips.power

import it.uhde.periph.connection.Connection
import java.io.IOException
import kotlin.math.sqrt

/**
 * ADE7953 — single-phase multifunction metering IC (Analog Devices).
 *
 * Reads the RMS voltage, RMS current on Current Channel A, and the
 * instantaneous / accumulated active power and energy. No register
 * configuration beyond the mandatory power-up sequence and the caller's
 * sensor-scaling constants is performed.
 *
 * Fixed I²C address: 0x38.
 *
 * The two calibration constants `voltageGain` and `currentGain` are
 * design-specific and must be supplied at construction (no universal
 * default).
 */
open class Ade7953Minimal @JvmOverloads constructor(
    protected val connection: Connection,
    voltageGain: Double,
    currentGain: Double
) {
    protected val voltageGain: Double = voltageGain
    protected var currentGainA: Double = currentGain
    protected var currentGainB: Double = currentGain
    protected var pgaA: Int = 1
    protected var pgaB: Int = 1
    protected var pgaV: Int = 1

    companion object {
        // 8-bit registers
        const val REG_DISNOLOAD     = 0x001
        const val REG_PGA_V         = 0x007
        const val REG_PGA_IA        = 0x008
        const val REG_PGA_IB        = 0x009
        const val REG_VERSION       = 0x702

        // 16-bit registers
        const val REG_CONFIG        = 0x102
        const val REG_PFA           = 0x10A
        const val REG_PERIOD        = 0x10E
        const val REG_INTERNAL_RES  = 0x120

        // 24-bit registers
        const val REG_AWATT         = 0x212
        const val REG_VRMS          = 0x21C
        const val REG_AENERGYA      = 0x21E
        const val REG_OVLVL         = 0x224
        const val REG_OILVL         = 0x225

        const val REG_120_UNLOCK    = 0xFE
        const val REG_120_VALUE     = 0x30

        const val ADC_FS_VOLTS      = 0.5 / sqrt(2.0)
        const val ADC_FS_CODE       = 9032007
        const val POWER_FS_CODE     = 4862401
        const val T_SAMPLE          = 1.0 / 206900.0
        const val PF_LSB            = 1.0 / 32768.0
        const val ANGLE_LSB         = 1.0 / 223750.0
    }

    init {
        initChip()
    }

    private fun initChip() {
        Thread.sleep(110)
        writeReg8(REG_INTERNAL_RES, REG_120_UNLOCK)
        writeReg16(REG_INTERNAL_RES, REG_120_VALUE)
    }

    protected fun readReg24(reg: Int): Int {
        val addr = byteArrayOf(((reg shr 8) and 0xFF).toByte(), (reg and 0xFF).toByte())
        val b = connection.writeRead(addr, 3)
        return ((b[0].toInt() and 0xFF) shl 16) or ((b[1].toInt() and 0xFF) shl 8) or (b[2].toInt() and 0xFF)
    }

    protected fun readReg24Signed(reg: Int): Int {
        var v = readReg24(reg)
        if ((v and 0x800000) != 0) v -= 0x1000000
        return v
    }

    protected fun readReg16(reg: Int): Int {
        val addr = byteArrayOf(((reg shr 8) and 0xFF).toByte(), (reg and 0xFF).toByte())
        val b = connection.writeRead(addr, 2)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    protected fun readReg16Signed(reg: Int): Int {
        var v = readReg16(reg)
        if ((v and 0x8000) != 0) v -= 0x10000
        return v
    }

    protected fun writeReg8(reg: Int, value: Int) {
        val buf = byteArrayOf(((reg shr 8) and 0xFF).toByte(), (reg and 0xFF).toByte(), (value and 0xFF).toByte())
        connection.write(buf)
    }

    protected fun writeReg16(reg: Int, value: Int) {
        val buf = byteArrayOf(((reg shr 8) and 0xFF).toByte(), (reg and 0xFF).toByte(),
                               ((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())
        connection.write(buf)
    }

    protected fun writeReg24(reg: Int, value: Int) {
        val buf = byteArrayOf(((reg shr 8) and 0xFF).toByte(), (reg and 0xFF).toByte(),
                               ((value shr 16) and 0xFF).toByte(),
                               ((value shr 8) and 0xFF).toByte(),
                               (value and 0xFF).toByte())
        connection.write(buf)
    }

    private fun voltageScale(): Double = (ADC_FS_VOLTS * voltageGain) / (ADC_FS_CODE * pgaV)
    private fun currentScale(gain: Double): Double = (ADC_FS_VOLTS * gain) / ADC_FS_CODE
    private fun powerScale(gain: Double): Double = ((ADC_FS_VOLTS * ADC_FS_VOLTS) * voltageGain * gain) / POWER_FS_CODE
    private fun energyScale(gain: Double): Double = ((ADC_FS_VOLTS * ADC_FS_VOLTS) * voltageGain * gain * T_SAMPLE) / 3600.0

    /** Read the RMS voltage on the voltage channel.
     *  @return Voltage in volts. */
    fun voltage(): Double = readReg24(REG_VRMS) * voltageScale()

    /** Read the RMS current on Current Channel A.
     *  @return Current in amperes. */
    fun current(): Double = readReg24(0x21A) * currentScale(currentGainA)

    /** Read instantaneous active power on Current Channel A.
     *  @return Active power in watts (signed). */
    fun activePower(): Double = readReg24Signed(REG_AWATT) * powerScale(currentGainA)

    /** Read the active-energy accumulator for Current Channel A.
     *  @return Active energy in watt-hours accumulated since the previous call. */
    fun activeEnergy(): Double = readReg24Signed(REG_AENERGYA) * energyScale(currentGainA)
}