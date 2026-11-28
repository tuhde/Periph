package it.uhde.periph.chips.light

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * TSL2561 two-channel ambient light sensor (TAOS / ams) — minimal interface.
 *
 * Channel 0 responds to visible and infrared light, channel 1 mainly to
 * infrared; the two 16-bit integrating ADC counts are combined with the
 * datasheet's empirical formula into lux. The I²C address is 0x29 (ADDR SEL =
 * GND), 0x39 (floating) or 0x49 (VDD).
 *
 * Every register access starts with a command byte (`0x80 | addr` for a byte,
 * `0xA0 | addr` for a little-endian word); both channels are read in one 4-byte
 * block read (`0x9C`) so the pair belongs to one integration. The constructor
 * powers the chip up with 1× gain and 402 ms integration and waits one
 * integration.
 *
 * @param connection I²C register connection bound to the device (0x29, 0x39 or 0x49)
 * @throws IOException on bus error, or if CONTROL does not read back as powered up
 */
open class TSL2561Minimal(protected val connection: RegisterConnection) {

    /** Thrown when a channel count is at or above the full-scale limit of the integration time. */
    class SaturatedException(val ch0: Int, val ch1: Int) :
        IOException("TSL2561: channel saturated (ch0=$ch0, ch1=$ch1)")

    companion object {
        /** 7-bit I²C address with ADDR SEL floating (default of most breakout boards). */
        const val DEFAULT_ADDRESS = 0x39

        /** 7-bit I²C address with ADDR SEL = GND. */
        const val ADDRESS_GND = 0x29

        /** 7-bit I²C address with ADDR SEL = VDD. */
        const val ADDRESS_VDD = 0x49

        /** Scale for every integration wait in percent (default 100; tests set 0 to skip the sleeps). */
        @Volatile
        @JvmStatic
        var waitPercent: Int = 100

        internal const val CMD = 0x80
        internal const val CMD_CLEAR = 0xC0
        internal const val CMD_WORD = 0xA0
        internal const val CMD_DATA_BLOCK = 0x9C
        internal const val REG_CONTROL = 0x0
        internal const val REG_TIMING = 0x1
        internal const val REG_THRESH_LOW = 0x2
        internal const val REG_THRESH_HIGH = 0x4
        internal const val REG_INTERRUPT = 0x6
        internal const val REG_ID = 0xA
        internal const val POWER_ON = 0x03
        internal const val INTEG_MANUAL = 0x03

        // Per INTEG code 0..2: nominal ms, scale, full-scale count, settle wait (one integration + ±6 % + margin).
        internal val INTEG_MS = intArrayOf(14, 101, 402)
        internal val INTEG_SCALE = doubleArrayOf(0.034, 0.252, 1.0)
        internal val INTEG_FULL_SCALE = intArrayOf(5047, 37177, 65535)
        internal val INTEG_SETTLE_MS = intArrayOf(20, 120, 450)

        private val COEFF_TFNCL = arrayOf(
            doubleArrayOf(0.50, 0.0304, 0.062), doubleArrayOf(0.61, 0.0224, 0.031),
            doubleArrayOf(0.80, 0.0128, 0.0153), doubleArrayOf(1.30, 0.00146, 0.00112))
        private val COEFF_CS = arrayOf(
            doubleArrayOf(0.52, 0.0315, 0.0593), doubleArrayOf(0.65, 0.0229, 0.0291),
            doubleArrayOf(0.80, 0.0157, 0.0180), doubleArrayOf(1.30, 0.00338, 0.00260))

        /**
         * Convert raw channel counts into lux with the datasheet's empirical formula.
         *
         * @param ch0 channel 0 count (visible + infrared)
         * @param ch1 channel 1 count (infrared)
         * @param gain 1 or 16
         * @param integrationMs 14, 101 or 402 (any other value is treated as 402 ms)
         * @param pkg "T", "FN", "CL" or "CS"
         * @return lux; 0 for ch0 = 0 or a ratio above 1.30
         */
        @JvmStatic
        fun calculateLux(ch0: Int, ch1: Int, gain: Int = 1, integrationMs: Int = 402, pkg: String = "T"): Double {
            val code = when (integrationMs) { 14 -> 0; 101 -> 1; else -> 2 }
            val factor = (16.0 / (if (gain == 16) 16.0 else 1.0)) / INTEG_SCALE[code]
            val ch0n = ch0 * factor
            val ch1n = ch1 * factor
            if (ch0n <= 0) return 0.0
            val r = ch1n / ch0n
            val c = if (pkg == "CS") COEFF_CS else COEFF_TFNCL
            val lux = when {
                r <= c[0][0] -> c[0][1] * ch0n - c[0][2] * ch0n * Math.pow(r, 1.4)
                r <= c[1][0] -> c[1][1] * ch0n - c[1][2] * ch1n
                r <= c[2][0] -> c[2][1] * ch0n - c[2][2] * ch1n
                r <= c[3][0] -> c[3][1] * ch0n - c[3][2] * ch1n
                else -> 0.0
            }
            return maxOf(0.0, lux)
        }

        internal fun sleepMs(ms: Int) {
            val scaled = ms.toLong() * waitPercent / 100
            if (scaled <= 0) return
            try {
                Thread.sleep(scaled)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    protected var curGain: Int = 1
    protected var integ: Int = 2
    protected var pkg: String = "T"

    init {
        writeByte(REG_CONTROL, POWER_ON)
        if (readByte(REG_CONTROL) and 0x03 != POWER_ON) {
            throw IOException("TSL2561: CONTROL did not read back as powered up")
        }
        writeTiming(false)
        sleepMs(settleMs())
    }

    protected fun readByte(reg: Int): Int = connection.read(CMD or reg, 1)[0].toInt() and 0xFF

    protected fun writeByte(reg: Int, value: Int) = connection.write(CMD or reg, byteArrayOf(value.toByte()))

    protected fun readWord(reg: Int): Int {
        val b = connection.read(CMD_WORD or reg, 2)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    protected fun writeWord(reg: Int, value: Int) =
        connection.write(CMD_WORD or reg, byteArrayOf(value.toByte(), (value shr 8).toByte()))

    protected fun writeTiming(manual: Boolean) {
        val v = (if (curGain == 16) 0x10 else 0x00) or integ or (if (manual) 0x08 else 0x00)
        writeByte(REG_TIMING, v)
    }

    protected fun readChannels(): Pair<Int, Int> {
        val b = connection.read(CMD_DATA_BLOCK, 4)
        return Pair((b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8),
            (b[2].toInt() and 0xFF) or ((b[3].toInt() and 0xFF) shl 8))
    }

    protected fun fullScale(): Int = if (integ < 3) INTEG_FULL_SCALE[integ] else 65535

    protected fun integrationMs(): Int = if (integ < 3) INTEG_MS[integ] else 0

    protected fun settleMs(): Int = if (integ < 3) INTEG_SETTLE_MS[integ] else 0

    protected fun luxFrom(ch0: Int, ch1: Int): Double {
        val limit = fullScale()
        if (ch0 >= limit || ch1 >= limit) throw SaturatedException(ch0, ch1)
        return calculateLux(ch0, ch1, curGain, integrationMs(), pkg)
    }

    /**
     * Read the illuminance.
     *
     * @return lux (T/FN/CL coefficients unless changed); 0 for a channel ratio above 1.30
     * @throws SaturatedException if a channel is at or above the full-scale limit
     */
    fun readLux(): Double {
        val (ch0, ch1) = readChannels()
        return luxFrom(ch0, ch1)
    }
}
