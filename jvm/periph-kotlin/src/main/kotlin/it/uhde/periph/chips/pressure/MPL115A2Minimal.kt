package it.uhde.periph.chips.pressure

import it.uhde.periph.connection.RegisterConnection
import java.io.IOException

/**
 * MPL115A2 — miniature I²C digital barometer, 50–115 kPa (minimal driver).
 *
 * Reads the compensated absolute pressure in pascals and an approximate temperature in degrees
 * Celsius. The four per-device compensation coefficients (a0, b1, b2, c12) are read once at
 * construction and cached; each reading writes CONVERT, waits 5 ms and reads the ADC registers.
 *
 * Fixed I²C address: 0x60. The chip has no configuration registers.
 *
 * @param connection I²C connection bound to address 0x60
 */
open class MPL115A2Minimal(protected val connection: RegisterConnection) {

    companion object {
        const val REG_PADC    = 0x00
        const val REG_COEF    = 0x04
        const val REG_CONVERT = 0x12

        /** Approximate temperature from the temperature count: `25 + (Tadc − 498) / −5.35`. */
        @JvmStatic
        protected fun temperatureC(tadc: Int): Double = 25.0 + (tadc - 498) / -5.35
    }

    protected var a0: Double = 0.0
    protected var b1: Double = 0.0
    protected var b2: Double = 0.0
    protected var c12: Double = 0.0

    init {
        loadCoefficients()
    }

    private fun word(d: ByteArray, i: Int): Int =
        ((d[i].toInt() and 0xFF) shl 8 or (d[i + 1].toInt() and 0xFF)).toShort().toInt()

    /**
     * Read the eight coefficient bytes and cache a0, b1, b2, c12.
     *
     * @throws IOException on I²C error
     */
    protected fun loadCoefficients() {
        val d = connection.read(REG_COEF, 8)
        a0  = word(d, 0) / 8.0
        b1  = word(d, 2) / 8192.0
        b2  = word(d, 4) / 16384.0
        c12 = word(d, 6) / 16777216.0
    }

    /**
     * Run one conversion and return the raw ADC counts.
     *
     * @return `{padc, tadc}`, each 0–1023
     * @throws IOException on I²C error
     */
    protected fun convert(): IntArray {
        connection.write(REG_CONVERT, byteArrayOf(0x00))
        Thread.sleep(5)
        val d = connection.read(REG_PADC, 4)
        val padc = ((d[0].toInt() and 0xFF) shl 8 or (d[1].toInt() and 0xFF)) shr 6
        val tadc = ((d[2].toInt() and 0xFF) shl 8 or (d[3].toInt() and 0xFF)) shr 6
        return intArrayOf(padc, tadc)
    }

    /**
     * Compute the pressure from raw counts and the cached coefficients (no bus access).
     *
     * @param padc 10-bit pressure ADC count
     * @param tadc 10-bit temperature ADC count
     * @return pressure in Pa
     */
    protected fun compute(padc: Int, tadc: Int): Double {
        val pcomp = a0 + (b1 + c12 * tadc) * padc + b2 * tadc
        return (pcomp * (115.0 - 50.0) / 1023.0 + 50.0) * 1000.0
    }

    /**
     * Run one conversion and return the compensated pressure.
     *
     * @return pressure in Pa
     * @throws IOException on I²C error
     */
    fun readPressure(): Double {
        val raw = convert()
        return compute(raw[0], raw[1])
    }

    /**
     * Run one conversion and return an approximate temperature (the datasheet gives no formula;
     * accurate to a few degrees — use for trends only).
     *
     * @return temperature in °C (approximate)
     * @throws IOException on I²C error
     */
    fun readTemperature(): Double = temperatureC(convert()[1])
}
