///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-kotlin:1.5.3

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.MPL115A2Full

fun main() {
    I2CConnection(1, 0x60).use { connection ->             // open I²C bus 1, device 0x60, (bus, address=0x60) → I2CConnection
        val sensor = MPL115A2Full(connection)              // construct driver, loads the coefficients, (connection) → MPL115A2Full

        val k = sensor.coefficients()                      // read cached coefficients, () → DoubleArray {a0, b1, b2, c12}
        println("a0=%.3f b1=%.5f b2=%.5f c12=%.8f".format(k[0], k[1], k[2], k[3]))

        // --- Barometer log ---
        // Reads the pressure every 2 s for 30 s, printing Pa, hPa, approximate
        // altitude and the raw counts, then the min/max of the run.
        var pmin = Double.MAX_VALUE
        var pmax = 0.0
        for (n in 0 until 15) {
            val pa = sensor.readPressure()                 // read pressure, () → Double Pa
            val hpa = sensor.pressureHpa()                 // read pressure, () → Double hPa
            val alt = sensor.altitude()                    // compute altitude, (seaLevelPa=101325.0) → Double m
            val raw = sensor.readRaw()                     // one conversion, () → IntArray {padc, tadc}
            pmin = minOf(pmin, pa)
            pmax = maxOf(pmax, pa)
            println("%ds: %.0f Pa, %.2f hPa, alt=%.1f m, raw=(%d, %d)".format(n * 2, pa, hpa, alt, raw[0], raw[1]))
            Thread.sleep(2000)
        }
        println("Summary: min=%.0f Pa, max=%.0f Pa".format(pmin, pmax))
    }
}
