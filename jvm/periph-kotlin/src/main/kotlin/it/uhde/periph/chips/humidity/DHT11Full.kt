package it.uhde.periph.chips.humidity

import it.uhde.periph.transport.Transport

/**
 * DHT11 -- temperature and humidity sensor (full driver).
 *
 * Extends DHT11Minimal with readTemperature(), readHumidity(),
 * readRetry(), and readRaw() methods.
 */
class DHT11Full(transport: Transport) : DHT11Minimal(transport) {

    fun readTemperature(): Double = read()[0]

    fun readHumidity(): Double = read()[1]

    fun readRetry(maxRetries: Int): DoubleArray {
        repeat(maxRetries) {
            try {
                return read()
            } catch (e: IOException) {
                // continue
            }
        }
        throw IOException("all retries exhausted")
    }

    fun readRaw(): ByteArray {
        val frame = transport.read(5)

        val humInt  = frame[0].toInt() and 0xFF
        val humDec  = frame[1].toInt() and 0xFF
        val tempInt = frame[2].toInt() and 0xFF
        val tempDec = frame[3].toInt() and 0xFF
        val checksum = frame[4].toInt() and 0xFF

        if ((humInt + humDec + tempInt + tempDec) and 0xFF != checksum) {
            throw IOException("checksum mismatch")
        }

        return frame
    }
}
