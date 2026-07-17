package it.uhde.periph.chips.gnss

import it.uhde.periph.transport.Transport
import java.io.IOException

/**
 * NEO-6 with UBX binary messaging, rate/platform configuration, and richer
 * NMEA fields (speed, course, UTC time/date, HDOP).
 *
 * Extends [Neo6Minimal]; all Minimal methods are inherited unchanged.
 */
class Neo6Full @JvmOverloads constructor(
    transport: Transport,
    busType: BusType = BusType.UART
) : Neo6Minimal(transport, busType) {

    companion object {
        private const val UBX_SYNC1 = 0xB5
        private const val UBX_SYNC2 = 0x62
        private const val CLASS_ACK = 0x05
        private const val ID_ACK_NAK = 0x00
        private const val MAX_FRAMES = 400
        private const val UBX_BYTE_TIMEOUT_MS = 4000
    }

    private var speedValue: Double? = null
    private var courseValue: Double? = null
    private var utcTimeValue: String? = null
    private var utcDateValue: String? = null
    private var hdopValue: Double? = null

    override fun handleExtra(sentenceId: String, fields: List<String>) {
        when (sentenceId) {
            "GGA" -> {
                if (fields.size > 1 && fields[1].isNotEmpty()) utcTimeValue = fields[1]
                if (fields.size > 8 && fields[8].isNotEmpty()) {
                    fields[8].toDoubleOrNull()?.let { hdopValue = it }
                }
            }
            "RMC" -> parseRmc(fields)
            "VTG" -> parseVtg(fields)
        }
    }

    private fun parseRmc(fields: List<String>) {
        if (fields.size < 10) return
        if (fields[1].isNotEmpty()) utcTimeValue = fields[1]
        if (fields[7].isNotEmpty()) fields[7].toDoubleOrNull()?.let { speedValue = it * 0.514444 }
        if (fields[8].isNotEmpty()) fields[8].toDoubleOrNull()?.let { courseValue = it }
        if (fields[9].isNotEmpty()) utcDateValue = fields[9]
    }

    private fun parseVtg(fields: List<String>) {
        if (fields.size > 1 && fields[1].isNotEmpty()) fields[1].toDoubleOrNull()?.let { courseValue = it }
        if (fields.size > 7 && fields[7].isNotEmpty()) fields[7].toDoubleOrNull()?.let { speedValue = it / 3.6 }
    }

    /** Speed over ground in m/s (from RMC/VTG), or null until the first speed field is parsed. */
    fun speed(): Double? = speedValue

    /** Course over ground in degrees 0-360 (from RMC/VTG), or null until the first course field is parsed. */
    fun course(): Double? = courseValue

    /** UTC time of the last GGA or RMC sentence, "hhmmss.ss", or null until parsed. */
    fun utcTime(): String? = utcTimeValue

    /** UTC date of the last RMC sentence, "ddmmyy", or null until parsed. */
    fun utcDate(): String? = utcDateValue

    /** Horizontal dilution of precision from the last GGA sentence, or null until parsed. */
    fun hdop(): Double? = hdopValue

    /** Frame and write a UBX message (adds sync bytes, length, checksum). */
    @JvmOverloads
    fun sendUbx(msgClass: Int, msgId: Int, payload: ByteArray = ByteArray(0)) {
        val length = payload.size
        val body = ByteArray(4 + length)
        body[0] = msgClass.toByte()
        body[1] = msgId.toByte()
        body[2] = (length and 0xFF).toByte()
        body[3] = ((length shr 8) and 0xFF).toByte()
        payload.copyInto(body, 4)
        val cs = ubxChecksum(body)
        val frame = ByteArray(2 + body.size + 2)
        frame[0] = UBX_SYNC1.toByte()
        frame[1] = UBX_SYNC2.toByte()
        body.copyInto(frame, 2)
        frame[frame.size - 2] = cs[0].toByte()
        frame[frame.size - 1] = cs[1].toByte()
        transport.write(frame)
    }

    /**
     * Send a poll request and return the response payload.
     *
     * @throws IOException if the module answers with ACK-NAK, no matching
     *   response arrives before the internal idle budget is spent, or a
     *   transport error occurs
     */
    fun pollUbx(msgClass: Int, msgId: Int): ByteArray {
        sendUbx(msgClass, msgId)
        return readUbxResponse(msgClass, msgId)
    }

    /**
     * Wait for one byte, retrying [readByte] (which is non-blocking) until
     * one arrives or [timeoutMs] of wall-clock time elapses. readByte()
     * alone cannot be used here: it returns immediately when the UART has
     * nothing buffered yet, so polling it in a bare loop with no pacing
     * burns through any iteration budget in microseconds rather than
     * giving the module real time to transmit.
     */
    private fun waitByte(timeoutMs: Int): Int? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val b = readByte()
            if (b != null) return b
            if (System.nanoTime() >= deadline) return null
            try {
                Thread.sleep(1)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("interrupted while waiting for UBX byte", e)
            }
        }
    }

    private fun readUbxResponse(wantClass: Int, wantId: Int): ByteArray {
        var frames = 0
        while (frames < MAX_FRAMES) {
            val b = waitByte(UBX_BYTE_TIMEOUT_MS) ?: throw IOException("UBX response timeout")
            if (b != UBX_SYNC1) continue
            val sync2 = waitByte(UBX_BYTE_TIMEOUT_MS)
            if (sync2 == null || sync2 != UBX_SYNC2) continue
            val cls = waitByte(UBX_BYTE_TIMEOUT_MS)
            val mid = waitByte(UBX_BYTE_TIMEOUT_MS)
            val lenLo = waitByte(UBX_BYTE_TIMEOUT_MS)
            val lenHi = waitByte(UBX_BYTE_TIMEOUT_MS)
            if (cls == null || mid == null || lenLo == null || lenHi == null) continue
            val length = lenLo or (lenHi shl 8)
            val payload = ByteArray(length)
            var got = 0
            while (got < length) {
                val pb = waitByte(UBX_BYTE_TIMEOUT_MS) ?: break
                payload[got] = pb.toByte()
                got++
            }
            if (got != length) {
                frames++
                continue
            }
            val ckA = waitByte(UBX_BYTE_TIMEOUT_MS)
            val ckB = waitByte(UBX_BYTE_TIMEOUT_MS)
            val checked = ByteArray(4 + length)
            checked[0] = cls.toByte()
            checked[1] = mid.toByte()
            checked[2] = lenLo.toByte()
            checked[3] = lenHi.toByte()
            payload.copyInto(checked, 4)
            val expected = ubxChecksum(checked)
            frames++
            if (ckA == null || ckB == null || ckA != expected[0] || ckB != expected[1]) continue
            if (cls == CLASS_ACK && mid == ID_ACK_NAK) {
                throw IOException("UBX NAK for class 0x%02X id 0x%02X".format(wantClass, wantId))
            }
            if (cls == wantClass && mid == wantId) return payload
        }
        throw IOException("UBX response timeout")
    }

    /** Set the navigation update rate via CFG-RATE. hz: 1-5 Hz for standard NEO-6 models. */
    fun setRate(hz: Int) {
        val measRateMs = 1000 / hz
        val payload = byteArrayOf(
            (measRateMs and 0xFF).toByte(), ((measRateMs shr 8) and 0xFF).toByte(),
            1, 0,
            0, 0
        )
        sendUbx(0x06, 0x08, payload)
    }

    /**
     * Set the dynamic platform model via CFG-NAV5.
     *
     * model: 0=portable, 2=stationary, 3=pedestrian, 4=automotive, 5=sea,
     * 6=airborne<1g, 7=airborne<2g, 8=airborne<4g.
     */
    fun setPlatform(model: Int) {
        val payload = ByteArray(36)
        payload[0] = 0x01 // mask: apply dynModel only
        payload[1] = 0x00
        payload[2] = (model and 0xFF).toByte()
        sendUbx(0x06, 0x24, payload)
    }

    /** Force a cold start via CFG-RST (clears almanac, ephemeris, and last known position). */
    fun coldStart() {
        val payload = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x02, 0x00)
        sendUbx(0x06, 0x04, payload)
    }

    /** Persist the current configuration via CFG-CFG (saves to battery-backed RAM and flash, where available). */
    fun saveConfig() {
        val payload = byteArrayOf(
            0, 0, 0, 0,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0, 0, 0, 0,
            0x07
        )
        sendUbx(0x06, 0x09, payload)
    }
}
