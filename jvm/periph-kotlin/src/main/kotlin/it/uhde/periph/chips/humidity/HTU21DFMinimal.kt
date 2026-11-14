package it.uhde.periph.chips.humidity

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * HTU21D-F — relative humidity and temperature sensor (Measurement Specialties), minimal driver.
 *
 * Each [read] triggers one temperature (0xF3) and one humidity (0xF5) measurement without hold
 * master, polls the NACKed read header until the chip ACKs (timeout 100 ms) and verifies the CRC
 * of both results (one retry on a mismatch). I²C address 0x40 (fixed).
 *
 * ## Configuration defaults
 * - Nothing is written at construction; [init] performs the soft reset
 * - Power-on defaults: 12-bit RH / 14-bit temperature, heater off
 * - No-hold-master measurement, polling the NACKed read header; CRC-8 verified
 * - Relative humidity is not clamped (ASIC range −6…118 %RH)
 */
open class HTU21DFMinimal(protected val connection: Connection) {
    companion object {
        const val CMD_T_HOLD     = 0xE3
        const val CMD_RH_HOLD    = 0xE5
        const val CMD_T_POLL     = 0xF3
        const val CMD_RH_POLL    = 0xF5
        const val CMD_WRITE_USER = 0xE6
        const val CMD_READ_USER  = 0xE7
        const val CMD_RESET      = 0xFE
        const val POLL_ATTEMPTS  = 100

        /**
         * CRC-8 used by the HTU21D-F: polynomial 0x131 (x^8 + x^5 + x^4 + 1), init 0x00, no final XOR.
         */
        fun crc8(data: ByteArray, off: Int, len: Int): Int {
            var crc = 0x00
            for (i in off until off + len) {
                crc = crc xor (data[i].toInt() and 0xFF)
                repeat(8) {
                    crc = if ((crc and 0x80) != 0) ((crc shl 1) xor 0x31) and 0xFF else (crc shl 1) and 0xFF
                }
            }
            return crc
        }

        fun toHumidity(code: Int): Double = -6.0 + 125.0 * code / 65536.0

        fun toTemperature(code: Int): Double = -46.85 + 175.72 * code / 65536.0
    }

    protected var hold = false

    protected fun sleepMs(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    protected fun command(code: Int) = connection.write(byteArrayOf(code.toByte()))

    /** Wait 15 ms for power-up, soft reset (0xFE) and wait 15 ms. */
    fun init() {
        sleepMs(15)
        command(CMD_RESET)
        sleepMs(15)
    }

    private fun measureOnce(holdCmd: Int, pollCmd: Int): ByteArray {
        if (hold) {
            command(holdCmd)
            return connection.read(3)
        }
        command(pollCmd)
        repeat(POLL_ATTEMPTS) {
            try {
                return connection.read(3)
            } catch (nack: IOException) {
                sleepMs(1)                          // read header NACKed: not done yet
            }
        }
        throw IOException("HTU21D-F measurement did not complete")
    }

    /** Run one measurement and return the CRC-verified raw 16-bit code with the status bits cleared. */
    protected fun measure(holdCmd: Int, pollCmd: Int): Int {
        repeat(2) {
            val data = measureOnce(holdCmd, pollCmd)
            if (crc8(data, 0, 2) == (data[2].toInt() and 0xFF)) {
                return (((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)) and 0xFFFC
            }
        }
        throw IOException("HTU21D-F CRC mismatch")
    }

    /**
     * Measure temperature and humidity.
     *
     * @return {temperature in °C, relative humidity in %RH (not clamped)}
     * @throws IOException on bus error, timeout (100 ms) or two CRC mismatches
     */
    fun read(): DoubleArray {
        val t = measure(CMD_T_HOLD, CMD_T_POLL)
        val rh = measure(CMD_RH_HOLD, CMD_RH_POLL)
        return doubleArrayOf(toTemperature(t), toHumidity(rh))
    }
}
