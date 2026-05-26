package it.uhde.periph.chips.humidity

import it.uhde.periph.transport.Transport

/**
 * DHT11 -- temperature and humidity sensor (minimal driver).
 *
 * DHT11 is a low-cost combined temperature and humidity sensor with factory-calibrated
 * digital output. Each read returns the result of the sensor's most recent completed
 * measurement, not a fresh instantaneous conversion.
 *
 * Note: This driver requires a GPIO-based transport (e.g. DHTxxTransport) that
 * implements the bit-bang protocol for the single-wire data line.
 */
open class DHT11Minimal(protected val transport: Transport) {

    fun read(): DoubleArray {
        val frame = transport.read(5)

        val humInt  = frame[0].toInt() and 0xFF
        val humDec  = frame[1].toInt() and 0xFF
        val tempInt = frame[2].toInt() and 0xFF
        val tempDec = frame[3].toInt() and 0xFF
        val checksum = frame[4].toInt() and 0xFF

        if ((humInt + humDec + tempInt + tempDec) and 0xFF != checksum) {
            throw IOException("checksum mismatch")
        }

        val humidity = humInt + humDec / 10.0
        val sign = if ((tempDec and 0x80) != 0) -1 else 1
        val tempDecValue = tempDec and 0x7F
        val temperature = sign * (tempInt + tempDecValue / 10.0)

        return doubleArrayOf(temperature, humidity)
    }
}

class IOException(message: String) : Exception(message)
