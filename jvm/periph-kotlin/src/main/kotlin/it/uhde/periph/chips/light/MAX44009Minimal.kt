package it.uhde.periph.chips.light

import it.uhde.periph.connection.RegisterConnection

/**
 * MAX44009 — ultra-low-power ambient light sensor with ADC (Maxim / Analog
 * Devices) — minimal interface.
 *
 * An on-chip photodiode with automatic ranging reports the illuminance as a
 * 12-bit floating-point value (4-bit exponent, 8-bit mantissa):
 * `lux = 2^E · M · 0.045`, from 0.045 lx to 188 006.4 lx. The I²C address is
 * 0x4A (A0 = GND) or 0x4B (A0 = VCC).
 *
 * Construction runs the initialization sequence (interrupt off, CONFIG 0x03,
 * thresholds fully open, timer 0xFF, INT_STATUS read once) and waits 900 ms so
 * the first [lux] returns a real measurement. A [RegisterConnection] read ends
 * with a STOP, so the lux registers are read as high, low, high and repeated
 * once if the high byte changed in between.
 *
 * @param connection I²C register connection bound to the device (0x4A or 0x4B)
 */
open class MAX44009Minimal(protected val connection: RegisterConnection) {

    companion object {
        /** 7-bit I²C address with A0 = GND. */
        const val DEFAULT_ADDRESS = 0x4A

        /** 7-bit I²C address with A0 = VCC. */
        const val ADDRESS_A0_HIGH = 0x4B

        /** Wait after the initialization writes, in ms (tests may set it to 0). */
        @Volatile
        @JvmStatic
        var initWaitMs: Int = 900

        const val REG_INT_STATUS = 0x00
        const val REG_INT_ENABLE = 0x01
        const val REG_CONFIG = 0x02
        const val REG_LUX_HIGH = 0x03
        const val REG_LUX_LOW = 0x04
        const val REG_UPPER = 0x05
        const val REG_LOWER = 0x06
        const val REG_TIMER = 0x07
        const val CFG_CONT = 0x80
        const val CFG_MANUAL = 0x40
        const val CFG_CDR = 0x08
        const val CFG_TIM_MASK = 0x07
        const val LUX_PER_LSB = 0.045
        const val MAX_LUX = 188006.4

        internal fun luxFrom(exponent: Int, mantissa: Int): Double =
            if (exponent >= 15) MAX_LUX else (1 shl exponent).toDouble() * mantissa * LUX_PER_LSB
    }

    protected var lastExponent: Int = 0

    init {
        writeReg(REG_INT_ENABLE, 0x00)
        writeReg(REG_CONFIG, 0x03)
        writeReg(REG_UPPER, 0xFF)
        writeReg(REG_LOWER, 0x00)
        writeReg(REG_TIMER, 0xFF)
        readReg(REG_INT_STATUS)
        if (initWaitMs > 0) {
            try {
                Thread.sleep(initWaitMs.toLong())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    protected fun readReg(reg: Int): Int = connection.read(reg, 1)[0].toInt() and 0xFF

    protected fun writeReg(reg: Int, value: Int) {
        connection.write(reg, byteArrayOf(value.toByte()))
    }

    /** Read LUX_HIGH, LUX_LOW and LUX_HIGH again; repeat once if the high byte changed. Returns (exponent, mantissa). */
    protected fun readLuxRegs(): Pair<Int, Int> {
        var high = readReg(REG_LUX_HIGH)
        var low = readReg(REG_LUX_LOW)
        val high2 = readReg(REG_LUX_HIGH)
        if (high2 != high) {
            high = high2
            low = readReg(REG_LUX_LOW)
        }
        val exponent = high shr 4
        val mantissa = ((high and 0x0F) shl 4) or (low and 0x0F)
        lastExponent = exponent
        return Pair(exponent, mantissa)
    }

    /**
     * Read the ambient illuminance.
     *
     * @return illuminance in lux (`2^E · M · 0.045`); the maximum 188006.4 lx on overrange (E = 15)
     */
    fun lux(): Double {
        val (e, m) = readLuxRegs()
        return luxFrom(e, m)
    }
}
