package it.uhde.periph.chips.power

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * LC709203F — 1-cell lithium-ion / polymer battery fuel gauge (onsemi), minimal driver.
 *
 * Reports cell voltage, state of charge and temperature. The constructor runs the datasheet
 * starting flow (operational mode, APA from the design capacity, battery profile, I²C temperature
 * mode with 25 °C). Every transfer carries a CRC-8 (polynomial 0x07, init 0x00, over the address
 * bytes as well) that the driver appends on writes and verifies on reads (one retry).
 *
 * I²C address: 0x0B (fixed). The chip stretches the clock.
 *
 * @param connection I²C connection bound to address 0x0B
 * @param capacityMah design capacity in mAh (clamped to 100–3000 for the APA interpolation)
 * @param profile battery profile written to register 0x12 (0 or 1)
 */
open class LC709203FMinimal(
    protected val connection: RegisterConnection,
    capacityMah: Int = 1000,
    profile: Int = 1
) {
    companion object {
        const val REG_BEFORE_RSOC   = 0x04
        const val REG_THERMISTOR_B  = 0x06
        const val REG_INITIAL_RSOC  = 0x07
        const val REG_TEMPERATURE   = 0x08
        const val REG_VOLTAGE       = 0x09
        const val REG_DIRECTION     = 0x0A
        const val REG_APA           = 0x0B
        const val REG_APT           = 0x0C
        const val REG_RSOC          = 0x0D
        const val REG_ITE           = 0x0F
        const val REG_IC_VERSION    = 0x11
        const val REG_PROFILE       = 0x12
        const val REG_ALARM_RSOC    = 0x13
        const val REG_ALARM_VOLTAGE = 0x14
        const val REG_POWER_MODE    = 0x15
        const val REG_STATUS        = 0x16
        const val REG_PROFILE_ID    = 0x1A

        private val APA_TABLE = arrayOf(
            intArrayOf(100, 0x08), intArrayOf(200, 0x0B), intArrayOf(500, 0x10),
            intArrayOf(1000, 0x19), intArrayOf(2000, 0x2D), intArrayOf(3000, 0x36)
        )

        /**
         * CRC-8 (ATM): polynomial 0x07, init 0x00, MSB first, no reflection, no final XOR.
         *
         * @param data bytes to checksum
         * @return CRC byte (0–255)
         */
        @JvmStatic
        fun crc8(vararg data: Int): Int {
            var crc = 0
            for (b in data) {
                crc = crc xor (b and 0xFF)
                repeat(8) {
                    crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
                }
            }
            return crc
        }

        /**
         * APA value interpolated from the datasheet table (Type-01/03) for a design capacity.
         *
         * @param capacityMah design capacity in mAh
         * @return APA register value (clamped to 100–3000 mAh)
         */
        @JvmStatic
        fun apaFor(capacityMah: Int): Int {
            val last = APA_TABLE.size - 1
            if (capacityMah <= APA_TABLE[0][0]) return APA_TABLE[0][1]
            if (capacityMah >= APA_TABLE[last][0]) return APA_TABLE[last][1]
            for (i in 1..last) {
                if (capacityMah <= APA_TABLE[i][0]) {
                    val c0 = APA_TABLE[i - 1][0].toDouble()
                    val a0 = APA_TABLE[i - 1][1].toDouble()
                    val c1 = APA_TABLE[i][0].toDouble()
                    val a1 = APA_TABLE[i][1].toDouble()
                    return Math.round(a0 + (a1 - a0) * (capacityMah - c0) / (c1 - c0)).toInt()
                }
            }
            return APA_TABLE[last][1]
        }
    }

    init {
        wakeAndOperational()
        writeWord(REG_APA, apaFor(capacityMah))
        writeWord(REG_PROFILE, profile and 1)
        pause(100) // TINIT: re-initialization like a battery insertion
        wakeAndOperational()
        writeWord(REG_STATUS, 0x0000)
        setTemperature(25.0)
    }

    /**
     * Read the cell voltage.
     *
     * @return cell voltage in V (1 mV LSB)
     */
    fun voltage(): Double = readWord(REG_VOLTAGE) / 1000.0

    /**
     * Read the state of charge from the ITE register.
     *
     * @return state of charge in % (0.0–100.0)
     */
    fun percent(): Double = readWord(REG_ITE) / 10.0

    /**
     * Read the cell temperature register.
     *
     * @return cell temperature in °C
     */
    fun temperature(): Double = readWord(REG_TEMPERATURE) / 10.0 - 273.15

    /**
     * Supply the cell temperature (I²C temperature mode).
     *
     * @param celsius cell temperature in °C, clamped to −20…+60
     */
    fun setTemperature(celsius: Double) {
        val c = Math.max(-20.0, Math.min(60.0, celsius))
        writeWord(REG_TEMPERATURE, Math.round((c + 273.15) * 10.0).toInt())
    }

    // ---- low-level helpers ----

    /** Write operational mode twice, ignoring bus errors (the first transfer may be lost while waking). */
    protected fun wakeAndOperational() {
        repeat(2) {
            try {
                writeWord(REG_POWER_MODE, 0x0001, false)
            } catch (e: IOException) {
                // ignored: wake-up pulse
            }
        }
        pause(1)
    }

    /**
     * Read a 16-bit word, verify the CRC-8 and retry once.
     *
     * @param cmd command code
     * @return word (0–65535)
     */
    protected fun readWord(cmd: Int): Int {
        var last: IOException? = null
        for (attempt in 0 until 2) {
            try {
                val b = connection.read(cmd, 3)
                val lo = b[0].toInt() and 0xFF
                val hi = b[1].toInt() and 0xFF
                if (crc8(0x16, cmd, 0x17, lo, hi) == (b[2].toInt() and 0xFF)) return lo or (hi shl 8)
                last = IOException("LC709203F CRC mismatch on command 0x%02X".format(cmd))
            } catch (e: IOException) {
                last = e
            }
        }
        throw last!!
    }

    /**
     * Write a 16-bit word with CRC-8.
     *
     * @param cmd command code
     * @param value 16-bit value
     * @param retry retry once on a bus error
     */
    protected fun writeWord(cmd: Int, value: Int, retry: Boolean = true) {
        val lo = value and 0xFF
        val hi = (value shr 8) and 0xFF
        val data = byteArrayOf(lo.toByte(), hi.toByte(), crc8(0x16, cmd, lo, hi).toByte())
        try {
            connection.write(cmd, data)
        } catch (e: IOException) {
            if (!retry) throw e
            connection.write(cmd, data)
        }
    }

    /**
     * Sleep for a number of milliseconds.
     *
     * @param ms duration in milliseconds
     */
    protected fun pause(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
