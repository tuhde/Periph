package it.uhde.periph.chips.environmental

import it.uhde.periph.connection.Connection
import java.io.IOException

/**
 * AM2315 — full driver extending [AM2315Minimal]: temperature-only and humidity-only reads, raw
 * words, the two-read real-time value, arbitrary register read/write (CRC verified), device
 * information, the user registers and an explicit wake-up.
 */
open class AM2315Full(connection: Connection) : AM2315Minimal(connection) {

    /** Read the temperature only (registers 0x02–0x03) in °C. */
    fun readTemperature(): Double = toTemperature(word(readRegistersRaw(0x02, 2), 0))

    /** Read the humidity only (registers 0x00–0x01) in %RH. */
    fun readHumidity(): Double = toHumidity(word(readRegistersRaw(0x00, 2), 0))

    /** Read the raw 16-bit words: {raw humidity, raw temperature (sign + magnitude)}. */
    fun readRaw(): IntArray {
        val d = readRegistersRaw(0x00, 4)
        return intArrayOf(word(d, 0), word(d, 2))
    }

    /** Read twice (at least 2 s apart) and return the second, real-time {temperature °C, humidity %RH}. */
    fun readFresh(): DoubleArray {
        read()
        return read()
    }

    /**
     * Read registers (function 0x03, CRC verified).
     *
     * @param start first register address (0x00–0x1F)
     * @param count number of registers, 1–10
     * @throws IllegalArgumentException if the range is invalid
     */
    fun readRegisters(start: Int, count: Int): ByteArray {
        require(count in 1..10 && start in 0..0x1F && start + count <= 0x20) { "invalid register range" }
        return readRegistersRaw(start, count)
    }

    /**
     * Write the user registers 0x10–0x13 (function 0x10 with CRC).
     *
     * @param start first register address (0x10–0x13)
     * @param data 1–4 bytes; `start + data.size` must not pass 0x14
     * @throws IllegalArgumentException if the range is not inside the user registers
     */
    fun writeRegisters(start: Int, data: ByteArray) {
        val n = data.size
        require(n in 1..4 && start >= 0x10 && start + n <= 0x14) { "only the user registers 0x10-0x13 may be written" }
        val frame = ByteArray(n + 5)
        frame[0] = FUNC_WRITE.toByte()
        frame[1] = start.toByte()
        frame[2] = n.toByte()
        data.copyInto(frame, 3)
        val crc = crc16(frame, 0, n + 3)
        frame[n + 3] = crc.toByte()
        frame[n + 4] = (crc shr 8).toByte()
        val resp = transact(frame, 5, FUNC_WRITE)
        if ((resp[1].toInt() and 0xFF) != start || (resp[2].toInt() and 0xFF) != n) throw IOException("AM2315 unexpected write response")
    }

    /** Read the device information (registers 0x08–0x0E): {model, version, device id}. */
    fun readDeviceInfo(): LongArray {
        val d = readRegistersRaw(0x08, 7)
        val id = ((d[3].toLong() and 0xFF) shl 24) or ((d[4].toLong() and 0xFF) shl 16) or
            ((d[5].toLong() and 0xFF) shl 8) or (d[6].toLong() and 0xFF)
        return longArrayOf(word(d, 0).toLong(), (d[2].toInt() and 0xFF).toLong(), id)
    }

    /** Read the two 16-bit user registers (0x10–0x13): {user1, user2}. */
    fun readUserRegisters(): IntArray {
        val d = readRegistersRaw(0x10, 4)
        return intArrayOf(word(d, 0), word(d, 2))
    }

    /** Write the two 16-bit user registers (0x10–0x13). */
    fun writeUserRegisters(user1: Int, user2: Int) {
        writeRegisters(0x10, byteArrayOf((user1 shr 8).toByte(), user1.toByte(), (user2 shr 8).toByte(), user2.toByte()))
    }

    /** Wake the sensor: a dummy write (the NACK is ignored) and a 1 ms wait. */
    fun wake() = wakeSensor()
}
