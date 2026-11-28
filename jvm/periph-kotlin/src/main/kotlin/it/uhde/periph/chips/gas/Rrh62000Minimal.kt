package it.uhde.periph.chips.gas

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/** RRH62000 data error: CRC mismatch, start-up (all-0xFF) block or timeout. */
class Rrh62000Exception(message: String) : IOException(message)

/**
 * Main air-quality values of one RRH62000 sample.
 *
 * @property pm1UgM3 PM1, KCl reference, µg/m³
 * @property pm2_5UgM3 PM2.5, KCl reference, µg/m³
 * @property pm10UgM3 PM10, KCl reference, µg/m³
 * @property temperatureC temperature, °C
 * @property humidityRh relative humidity, %RH
 * @property tvoc TVOC, µg/m³ (LSB 10 µg/m³)
 * @property eco2Ppm estimated CO₂, ppm (400–5000)
 * @property iaq UBA indoor air quality index, 1.00–5.00
 */
data class Rrh62000Measurement(
    val pm1UgM3: Double,
    val pm2_5UgM3: Double,
    val pm10UgM3: Double,
    val temperatureC: Double,
    val humidityRh: Double,
    val tvoc: Int,
    val eco2Ppm: Int,
    val iaq: Double,
)

/**
 * The decoded status word of the RRH62000 measurement block.
 *
 * @property raw status word (`byte0 | byte1 << 8`)
 * @property highConcentration bit 0: dust concentration extremely high (not measurable)
 * @property dustAccumulation bit 1: dust accumulated inside the sensor (not measurable)
 * @property fanSpeed bit 2: fan speed out of the set range
 * @property fan bit 3: fan malfunction or breakdown
 */
data class Rrh62000Status(
    val raw: Int,
    val highConcentration: Boolean,
    val dustAccumulation: Boolean,
    val fanSpeed: Boolean,
    val fan: Boolean,
)

/**
 * Particle values of the RRH62000 measurement block.
 *
 * @property pm1UgM3 PM1, KCl reference, µg/m³
 * @property pm2_5UgM3 PM2.5, KCl reference, µg/m³
 * @property pm10UgM3 PM10, KCl reference, µg/m³
 * @property pm1SmokeUgM3 PM1, cigarette-smoke reference, µg/m³
 * @property pm2_5SmokeUgM3 PM2.5, cigarette-smoke reference, µg/m³
 * @property pm10SmokeUgM3 PM10, cigarette-smoke reference, µg/m³
 * @property nc0_3 particles 0.3–10 µm per cm³
 * @property nc0_5 particles 0.5–10 µm per cm³
 * @property nc1 particles 1–10 µm per cm³
 * @property nc2_5 particles 2.5–10 µm per cm³
 * @property nc4 particles 4–10 µm per cm³
 */
data class Rrh62000Particles(
    val pm1UgM3: Double,
    val pm2_5UgM3: Double,
    val pm10UgM3: Double,
    val pm1SmokeUgM3: Double,
    val pm2_5SmokeUgM3: Double,
    val pm10SmokeUgM3: Double,
    val nc0_3: Double,
    val nc0_5: Double,
    val nc1: Double,
    val nc2_5: Double,
    val nc4: Double,
)

/**
 * The complete decoded RRH62000 measurement block.
 *
 * @property status status word and flags
 * @property particles PM values (both references) and number concentrations
 * @property temperatureC temperature, °C
 * @property humidityRh relative humidity, %RH
 * @property tvoc TVOC, µg/m³
 * @property eco2Ppm estimated CO₂, ppm
 * @property iaq UBA IAQ index, 1.00–5.00
 */
data class Rrh62000Data(
    val status: Rrh62000Status,
    val particles: Rrh62000Particles,
    val temperatureC: Double,
    val humidityRh: Double,
    val tvoc: Int,
    val eco2Ppm: Int,
    val iaq: Double,
)

/**
 * RRH62000 — all-in-one air-quality module (Renesas), minimal driver.
 *
 * One call returns PM1/PM2.5/PM10 (KCl reference), temperature, humidity, TVOC, estimated CO₂ and
 * the UBA IAQ index. Over I²C (address 0x69, SEL pin to GND) the module is register addressed: a
 * one-byte command code is the register pointer; reading 0x00 returns the 37-byte measurement block
 * with a CRC-8. The module must not be accessed more often than once per second (tRD); every method
 * waits for the access interval. The start-up time (1 s for I²C, 3.3 s for valid data) is counted
 * from construction, `wake()` and `softReset()`. The module defaults (10 moving averages, fan 86 %,
 * cleaning every 7 days for 10 s) are kept. The constructor does no bus access.
 *
 * @param connection register connection (1-byte register addresses) bound to address 0x69
 * @param accessIntervalMs minimum spacing between two accesses (the module needs ≥ 1000 ms; lower
 *   it only for mocks)
 */
open class Rrh62000Minimal @JvmOverloads constructor(
    protected val connection: RegisterConnection,
    private val accessIntervalMs: Long = 1000,
) {
    companion object {
        /** Fixed 7-bit I²C address. */
        const val ADDRESS = 0x69
        /** Length of the measurement block. */
        const val BLOCK_LENGTH = 37

        internal const val CMD_READ = 0x00
        internal const val CMD_DATA = 0x40
        internal const val CMD_SLEEP = 0x50
        internal const val CMD_CLEAN = 0x51
        internal const val CMD_RESET = 0x52
        internal const val CMD_MAVE = 0x53
        internal const val CMD_TINTC_H = 0x5A
        internal const val CMD_TINTC_L = 0x5B
        internal const val CMD_TCLEAN = 0x5C
        internal const val CMD_SPEEDFAN = 0x63
        internal const val CMD_RMOX = 0x71
        internal const val CMD_UID = 0x72
        internal const val CMD_ARGVER = 0x73
        internal const val CMD_CSTATUS = 0x74
        internal const val CMD_FWVER = 0x75

        private const val STARTUP_MS = 1000L
        private const val VALID_MS = 3300L

        /**
         * CRC-8 of the measurement block (polynomial 0x31, init 0xFF; `crc8([0xBE, 0xEF]) == 0x92`).
         *
         * @param data bytes
         * @param length number of bytes from the start of [data]
         * @return the CRC
         */
        @JvmStatic
        fun crc8(data: ByteArray, length: Int = data.size): Int {
            var crc = 0xFF
            for (i in 0 until length) {
                crc = crc xor (data[i].toInt() and 0xFF)
                repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x31) and 0xFF else (crc shl 1) and 0xFF }
            }
            return crc
        }

        /**
         * Decodes a 37-byte measurement block.
         *
         * @param b the raw block
         * @return the decoded values
         * @throws Rrh62000Exception on a wrong length, an all-0xFF block or a CRC mismatch
         */
        @JvmStatic
        fun decode(b: ByteArray): Rrh62000Data {
            if (b.size != BLOCK_LENGTH) throw Rrh62000Exception("RRH62000 block must be 37 bytes")
            if ((0 until 36).all { b[it].toInt() and 0xFF == 0xFF }) throw Rrh62000Exception("RRH62000 sample invalid (start-up)")
            if (crc8(b, 36) != b[36].toInt() and 0xFF) throw Rrh62000Exception("RRH62000 CRC error")
            fun w(i: Int) = (b[i].toInt() and 0xFF shl 8) or (b[i + 1].toInt() and 0xFF)
            val raw = (b[0].toInt() and 0xFF) or (b[1].toInt() and 0xFF shl 8)
            return Rrh62000Data(
                Rrh62000Status(raw, raw and 0x01 != 0, raw and 0x02 != 0, raw and 0x04 != 0, raw and 0x08 != 0),
                Rrh62000Particles(
                    w(12) / 10.0, w(14) / 10.0, w(16) / 10.0, w(18) / 10.0, w(20) / 10.0, w(22) / 10.0,
                    w(2) / 10.0, w(4) / 10.0, w(6) / 10.0, w(8) / 10.0, w(10) / 10.0,
                ),
                w(24).toShort() / 100.0,
                w(26) / 100.0,
                w(28) * 10,
                w(30),
                w(32) / 100.0,
            )
        }

        private fun now() = System.nanoTime() / 1_000_000L

        private fun sleepMs(ms: Long) {
            if (ms <= 0) return
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("interrupted", e)
            }
        }
    }

    private var lastAccess = 0L
    private var validAt = 0L

    /** Timeout of the DATA polling of [read] in ms (default 4000). */
    internal var dataTimeoutMs = 4000L

    init {
        markStartup()
    }

    /** Restarts the start-up timers (power-up, reset, wake-up); the waits scale with the interval. */
    protected fun markStartup() {
        val t = now()
        lastAccess = t + STARTUP_MS * accessIntervalMs / 1000 - accessIntervalMs
        validAt = t + VALID_MS * accessIntervalMs / 1000
    }

    protected fun readCmd(cmd: Int, length: Int): ByteArray {
        sleepMs(accessIntervalMs - (now() - lastAccess))
        try {
            return connection.read(cmd, length)
        } finally {
            lastAccess = now()
        }
    }

    protected fun readByte(cmd: Int): Int = readCmd(cmd, 1)[0].toInt() and 0xFF

    protected fun writeCmd(cmd: Int, value: Int) {
        sleepMs(accessIntervalMs - (now() - lastAccess))
        try {
            connection.write(cmd, byteArrayOf(value.toByte()))
        } finally {
            lastAccess = now()
        }
    }

    protected fun readBlock(waitData: Boolean): Rrh62000Data {
        sleepMs(validAt - now())
        if (waitData) {
            val start = now()
            while (readByte(CMD_DATA) != 0x01) {
                if (now() - start >= dataTimeoutMs) throw Rrh62000Exception("RRH62000 no new data within 4 s")
            }
        }
        return decode(readCmd(CMD_READ, BLOCK_LENGTH))
    }

    /**
     * Waits for the start-up time (1 s from construction) and reads the firmware version as a
     * presence check.
     *
     * @throws IOException on a bus error
     */
    @Synchronized
    fun init() {
        readCmd(CMD_FWVER, 2)
    }

    /**
     * Waits for a new sample (DATA polled once per second, up to 4 s) and returns the main values.
     *
     * @return PM (KCl reference), temperature, humidity, TVOC, eCO₂ and IAQ
     * @throws Rrh62000Exception on a timeout, a CRC mismatch or a start-up block
     */
    @Synchronized
    fun read(): Rrh62000Measurement {
        val d = readBlock(true)
        val p = d.particles
        return Rrh62000Measurement(p.pm1UgM3, p.pm2_5UgM3, p.pm10UgM3, d.temperatureC, d.humidityRh, d.tvoc, d.eco2Ppm, d.iaq)
    }
}
