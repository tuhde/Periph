package it.uhde.periph.chips.environmental

import it.uhde.periph.transport.Transport

/**
 * AHT21 — full driver. Extends [Aht21Minimal] with CRC verification,
 * explicit soft reset, calibration status inspection, and individual
 * temperature/humidity readings.
 */
class Aht21Full(
    transport: Transport
) : Aht21Minimal(transport) {

    /**
     * Trigger a measurement and return temperature only.
     *
     * @return temperature in °C (-50 to 150 °C)
     */
    fun readTemperature(): Double = read().first

    /**
     * Trigger a measurement and return humidity only.
     *
     * @return relative humidity in %RH (0 to 100 %RH)
     */
    fun readHumidity(): Double = read().second

    /**
     * Trigger a measurement, read 7 bytes, and verify CRC-8.
     *
     * Uses polynomial x^8 + x^5 + x^4 + 1 (0x31) with initial value 0xFF
     * to verify the CRC byte against bytes 0–5 of the response.
     *
     * @return Triple of (temperature in °C, humidity in %RH, crc_ok)
     */
    fun readWithCrc(): Triple<Double, Double, Boolean> {
        transport.write(CMD_TRIGGER)
        Thread.sleep(80)
        val data = transport.read(7)
        val (t, h) = decode(data)
        val crcOk = crc8(data, 6) == (data[6].toInt() and 0xFF)
        return Triple(t, h, crcOk)
    }

    /**
     * Send the soft reset command and wait 20 ms for recovery.
     */
    fun softReset() {
        transport.write(CMD_SOFT_RESET)
        Thread.sleep(20)
    }

    /**
     * Check if the calibration bit is set in the status byte.
     *
     * @return true if the sensor reports calibration enabled
     */
    fun isCalibrated(): Boolean = (readStatus() and STATUS_CAL) != 0

    /**
     * Check if the busy bit is set in the status byte.
     *
     * @return true if a measurement is in progress
     */
    fun isBusy(): Boolean = (readStatus() and STATUS_BUSY) != 0

    private fun crc8(data: ByteArray, len: Int): Int {
        var crc = 0xFF
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (j in 0 until 8) {
                crc = if ((crc and 0x80) != 0)
                    ((crc shl 1) xor 0x31) and 0xFF
                else
                    (crc shl 1) and 0xFF
            }
        }
        return crc
    }
}
