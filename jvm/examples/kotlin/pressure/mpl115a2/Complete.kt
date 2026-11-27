///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-kotlin:1.6.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.MPL115A2Full

fun main() {
    I2CConnection(1, 0x60).use { connection ->             // open I²C bus 1, device 0x60, (bus, address=0x60) → I2CConnection
        val sensor = MPL115A2Full(connection)              // construct driver, loads the coefficients, (connection) → MPL115A2Full

        val k = sensor.coefficients()                      // read cached coefficients, () → DoubleArray {a0, b1, b2, c12}
        sensor.reloadCoefficients()                        // re-read coefficient block, () → Unit
                                                           // use after a power cycle
        val both = sensor.read()                           // one conversion, () → DoubleArray {pressure_pa, temperature_c}
        val raw = sensor.readRaw()                         // one conversion, () → IntArray {padc, tadc} 10-bit counts
        val p = sensor.compensate(raw[0], raw[1])          // compensate raw counts, (padc, tadc) → Double Pa
                                                           // pure function, no bus access
        val hpa = sensor.pressureHpa()                     // read pressure, () → Double hPa
        val alt = sensor.altitude()                        // compute altitude, (seaLevelPa=101325.0) → Double m
                                                           // barometric formula, informative
        println("a0=%.3f b1=%.5f b2=%.5f c12=%.8f".format(k[0], k[1], k[2], k[3]))
        println("%.0f Pa  %.1f °C  raw=(%d, %d)  %.0f Pa  %.2f hPa  alt=%.1f m".format(
            both[0], both[1], raw[0], raw[1], p, hpa, alt))
    }
}
