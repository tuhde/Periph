package it.uhde.periph.chips.gnss

import it.uhde.periph.transport.Transport
import it.uhde.periph.transport.UARTTransport

/**
 * Transport kind [Neo6Minimal] was constructed with.
 *
 * The JVM ecosystem has no SPI transport, so only UART and I2C (DDC) are offered.
 */
enum class BusType { UART, I2C }

/**
 * u-blox NEO-6 GNSS receiver: NMEA position, altitude, and fix status (minimal driver).
 *
 * Reads bytes from the transport and assembles complete NMEA sentences
 * terminated by CR/LF. Works out of the box with the module's factory
 * defaults (NMEA output at 9600 baud, 1 Hz, all standard sentences enabled)
 * -- no chip-side configuration is sent.
 *
 * A stray idle-filler byte (0xFF on I2C when the module has nothing queued)
 * can never start a sentence (NMEA sentences start with '$'); if one lands
 * mid-sentence during a buffer underrun, the resulting sentence simply
 * fails its checksum and is discarded, same as any other corrupted sentence.
 */
open class Neo6Minimal @JvmOverloads constructor(
    protected val transport: Transport,
    protected val busType: BusType = BusType.UART
) {
    companion object {
        const val SENTENCE_START = 0x24 // '$'
        const val CR = 0x0D
        const val LF = 0x0A
        const val MAX_SENTENCE = 96

        internal fun checksumOk(sentence: ByteArray): Boolean {
            val star = sentence.indexOf('*'.code.toByte())
            if (star < 1 || star + 4 > sentence.size) return false
            var checksum = 0
            for (i in 1 until star) checksum = checksum xor (sentence[i].toInt() and 0xFF)
            return try {
                val expected = String(sentence, star + 1, 2, Charsets.US_ASCII).toInt(16)
                checksum == expected
            } catch (e: NumberFormatException) {
                false
            }
        }

        internal fun nmeaToDegrees(raw: String, hemisphere: String): Double {
            val value = raw.toDouble()
            val deg = (value / 100).toInt()
            val minutes = value - deg * 100
            var decimal = deg + minutes / 60.0
            if (hemisphere == "S" || hemisphere == "W") decimal = -decimal
            return decimal
        }

        internal fun parseIntOrZero(s: String?): Int {
            if (s.isNullOrEmpty()) return 0
            return s.toIntOrNull() ?: 0
        }

        /** 8-bit Fletcher checksum over class, id, length, and payload bytes. */
        internal fun ubxChecksum(data: ByteArray): IntArray {
            var ckA = 0
            var ckB = 0
            for (b in data) {
                ckA = (ckA + (b.toInt() and 0xFF)) and 0xFF
                ckB = (ckB + ckA) and 0xFF
            }
            return intArrayOf(ckA, ckB)
        }
    }

    private val buf = ByteArray(MAX_SENTENCE)
    private var bufLen = 0
    private var inSentence = false

    private var lat: Double? = null
    private var lon: Double? = null
    private var alt: Double? = null
    private var fixValue: Int = 0
    private var satellitesValue: Int = 0

    /** Fetch one byte if available, or null if none is ready yet. */
    protected fun readByte(): Int? {
        return if (busType == BusType.UART) {
            val uart = transport as UARTTransport
            if (uart.available() <= 0) return null
            val b = transport.read(1)
            if (b.isNotEmpty()) b[0].toInt() and 0xFF else null
        } else {
            // DDC random-read: set the register pointer to 0xFF, then read one
            // stream byte. The pointer saturates at 0xFF once set, so
            // re-sending it on every byte is redundant but harmless.
            val b = transport.writeRead(byteArrayOf(0xFF.toByte()), 1)
            if (b.isNotEmpty()) b[0].toInt() and 0xFF else null
        }
    }

    /**
     * Read available bytes and parse at most one complete NMEA sentence.
     *
     * @return true if a GGA sentence with a valid fix (fix status > 0) was parsed
     */
    fun update(): Boolean {
        val byteVal = readByte() ?: return false
        if (byteVal == SENTENCE_START) {
            buf[0] = byteVal.toByte()
            bufLen = 1
            inSentence = true
            return false
        }
        if (!inSentence) return false
        if (bufLen >= MAX_SENTENCE) {
            bufLen = 0
            inSentence = false
            return false
        }
        buf[bufLen++] = byteVal.toByte()
        if (byteVal == LF && bufLen >= 2 && buf[bufLen - 2] == CR.toByte()) {
            val sentence = buf.copyOf(bufLen)
            bufLen = 0
            inSentence = false
            return onSentence(sentence)
        }
        return false
    }

    private fun onSentence(sentence: ByteArray): Boolean {
        if (!checksumOk(sentence)) return false
        val star = sentence.indexOf('*'.code.toByte())
        if (star < 1) return false
        val body = String(sentence, 1, star - 1, Charsets.US_ASCII)
        val fields = body.split(",")
        if (fields[0].length < 5) return false
        val sentenceId = fields[0].substring(2, 5)
        var result = false
        if (sentenceId == "GGA") {
            result = parseGga(fields)
        }
        handleExtra(sentenceId, fields)
        return result
    }

    private fun parseGga(fields: List<String>): Boolean {
        if (fields.size < 15) return false
        val f = parseIntOrZero(fields[6])
        fixValue = f
        satellitesValue = parseIntOrZero(fields[7])
        if (f > 0 && fields[2].isNotEmpty() && fields[3].isNotEmpty() &&
            fields[4].isNotEmpty() && fields[5].isNotEmpty()
        ) {
            try {
                lat = nmeaToDegrees(fields[2], fields[3])
                lon = nmeaToDegrees(fields[4], fields[5])
                alt = if (fields[9].isEmpty()) null else fields[9].toDouble()
            } catch (ignored: NumberFormatException) {
                // leave previous values in place
            }
        }
        return f > 0
    }

    /** Hook for [Neo6Full] to parse additional sentence types. No-op here. */
    protected open fun handleExtra(sentenceId: String, fields: List<String>) {
        // no-op in Minimal
    }

    /** Latitude of the last valid fix, decimal degrees positive north, or null until the first fix. */
    fun latitude(): Double? = lat

    /** Longitude of the last valid fix, decimal degrees positive east, or null until the first fix. */
    fun longitude(): Double? = lon

    /** Height above mean sea level of the last valid fix in meters, or null until the first fix. */
    fun altitude(): Double? = alt

    /** GGA fix quality of the last parsed GGA sentence: 0=no fix, 1=GPS, 2=DGPS. */
    fun fix(): Int = fixValue

    /** Number of satellites used in the last GGA fix (GGA field 7). */
    fun satellites(): Int = satellitesValue
}
