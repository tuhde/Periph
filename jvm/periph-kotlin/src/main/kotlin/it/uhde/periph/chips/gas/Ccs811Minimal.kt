package it.uhde.periph.chips.gas

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * CCS811 — indoor air quality gas sensor (ScioSense / ams), minimal driver.
 *
 * One call returns the equivalent CO₂ (eCO₂) and total VOC (TVOC) values of the chip's internal
 * algorithm. The constructor does no bus access; [init] verifies HW_ID (0x81), writes APP_START if
 * the chip is still in boot mode and selects drive mode 1 (MEAS_MODE = 0x10: one sample per second,
 * interrupts off). No environment data is written (the chip assumes 50 %RH and 25 °C). The chip
 * stretches the I²C clock; tie nWAKE low. New sensors need a 48 h burn-in and values settle about
 * 20 min after each power-up. I²C address 0x5A (ADDR low) or 0x5B (ADDR high).
 *
 * @param connection I²C register connection bound to address 0x5A or 0x5B
 */
open class Ccs811Minimal(protected val connection: RegisterConnection) {
    companion object {
        const val REG_STATUS          = 0x00
        const val REG_MEAS_MODE       = 0x01
        const val REG_ALG_RESULT_DATA = 0x02
        const val REG_RAW_DATA        = 0x03
        const val REG_ENV_DATA        = 0x05
        const val REG_NTC             = 0x06
        const val REG_THRESHOLDS      = 0x10
        const val REG_BASELINE        = 0x11
        const val REG_HW_ID           = 0x20
        const val REG_HW_VERSION      = 0x21
        const val REG_FW_BOOT_VERSION = 0x23
        const val REG_FW_APP_VERSION  = 0x24
        const val REG_ERROR_ID        = 0xE0
        const val REG_APP_START       = 0xF4
        const val REG_SW_RESET        = 0xFF

        const val HW_ID  = 0x81
        const val MODE_1 = 0x10

        /** STATUS.FW_MODE: application mode. */
        const val STATUS_FW_MODE    = 0x80
        /** STATUS.APP_VALID: valid application firmware loaded. */
        const val STATUS_APP_VALID  = 0x10
        /** STATUS.DATA_READY: new sample in ALG_RESULT_DATA. */
        const val STATUS_DATA_READY = 0x08
        /** STATUS.ERROR: see ERROR_ID. */
        const val STATUS_ERROR      = 0x01

        /** Sample interval in ms per drive mode (0 = idle). */
        val INTERVAL_MS = longArrayOf(0, 1000, 10000, 60000, 250)

        internal fun sleepMs(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /** One algorithm sample: equivalent CO₂ in ppm (400–8192) and total VOC in ppb (0–1187). */
    data class Measurement(val eco2Ppm: Int, val tvocPpb: Int)

    protected var measMode = MODE_1

    protected fun readByte(reg: Int): Int = connection.read(reg, 1)[0].toInt() and 0xFF

    protected fun read16(reg: Int): Int {
        val b = connection.read(reg, 2)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    protected fun write(reg: Int, vararg data: Int) =
        connection.write(reg, ByteArray(data.size) { data[it].toByte() })

    /**
     * Verify the chip, start the application firmware and select drive mode 1.
     *
     * @throws IOException on I²C error, a wrong HW_ID, missing application firmware, or a chip
     *                     that does not enter application mode
     */
    @Throws(IOException::class)
    fun init() {
        val id = readByte(REG_HW_ID)
        if (id != HW_ID) throw IOException("Unexpected HW_ID 0x%02X, expected 0x81".format(id))
        startApp()
        measMode = MODE_1
        write(REG_MEAS_MODE, measMode)
    }

    private fun startApp() {
        val status = readByte(REG_STATUS)
        if (status and STATUS_APP_VALID == 0) throw IOException("CCS811 has no valid application firmware")
        if (status and STATUS_FW_MODE != 0) return
        write(REG_APP_START)
        sleepMs(2)
        if (readByte(REG_STATUS) and STATUS_FW_MODE == 0) throw IOException("CCS811 did not enter application mode")
    }

    private fun waitDataReady() {
        val mode = (measMode shr 4) and 0x07
        val limit = maxOf(1500L, if (mode < INTERVAL_MS.size) INTERVAL_MS[mode] * 3 / 2 else 0L)
        val deadline = System.currentTimeMillis() + limit
        while (readByte(REG_STATUS) and STATUS_DATA_READY == 0) {
            if (System.currentTimeMillis() > deadline) throw IOException("CCS811 data ready timeout")
            sleepMs(20)
        }
    }

    private fun readResult(): Measurement {
        val b = connection.read(REG_ALG_RESULT_DATA, 5)
        if (b[4].toInt() and STATUS_ERROR != 0) {
            throw IOException("CCS811 error, ERROR_ID 0x%02X".format(readByte(REG_ERROR_ID)))
        }
        val u = { i: Int -> b[i].toInt() and 0xFF }
        return Measurement((u(0) shl 8) or u(1), (u(2) shl 8) or u(3))
    }

    /**
     * Wait for the next sample (DATA_READY) and read eCO₂ and TVOC.
     *
     * @return eCO₂ in ppm and TVOC in ppb
     * @throws IOException on I²C error, a timeout, or a chip-reported error (the message carries ERROR_ID)
     */
    @Throws(IOException::class)
    fun read(): Measurement {
        waitDataReady()
        return readResult()
    }
}
