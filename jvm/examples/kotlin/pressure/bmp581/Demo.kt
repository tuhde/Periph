///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Bmp581Full

fun main() {
    I2CConnection(1, 0x46).use { connection ->                  // open I²C bus 1, device 0x46, (bus, address=0x46) → I2CConnection

        // --- Precision altimeter: 10 Hz NORMAL mode for 30 seconds ---
        val bmp = Bmp581Full(connection)                            // construct driver, (connection) → Bmp581Full
        bmp.configure(0x17, Bmp581Full.OSR_16X, Bmp581Full.OSR_4X, true) // configure, (odr=10Hz, osr_p=×16, osr_t=×4, press_en) → Unit

        val pressures = DoubleArray(300)
        val temps = DoubleArray(300)
        val alts = DoubleArray(300)
        for (n in 0 until 300) {
            pressures[n] = bmp.pressure()                            // read pressure, () → Double Pa
            temps[n] = bmp.temperature()                             // read temperature, () → Double °C
            alts[n] = bmp.altitude()                                 // compute altitude, (seaLevelPa=101325.0) → Double
            if (n % 10 == 0) {
                val start = maxOf(0, n - 10)
                val span = minOf(10, n)
                if (span > 0) {
                    val mp = pressures.slice(start until n).average()
                    val mt = temps.slice(start until n).average()
                    val ma = alts.slice(start until n).average()
                    println("${n / 10}0s: rolling P=%.1f Pa  T=%.2f C  alt=%.2f m".format(mp, mt, ma))
                }
            }
            Thread.sleep(100)
        }
        val amin = alts.min(); val amax = alts.max()
        println("Bypass: alt min=%.3f max=%.3f spread=%.3f m".format(amin, amax, amax - amin))

        bmp.setIirFilter(Bmp581Full.IIR_COEFF_3, Bmp581Full.IIR_BYPASS) // set IIR filter, (coeff_p=7-tap, coeff_t=bypass) → Unit

        val alts2 = DoubleArray(300)
        for (n in 0 until 300) {
            bmp.pressure()                                            // read pressure, () → Double Pa
            alts2[n] = bmp.altitude()                                 // compute altitude, (seaLevelPa=101325.0) → Double
            Thread.sleep(100)
        }
        val amin2 = alts2.min(); val amax2 = alts2.max()
        println("IIR=3:  alt min=%.3f max=%.3f spread=%.3f m".format(amin2, amax2, amax2 - amin2))

        val pmin = pressures.min(); val pmax = pressures.max()
        val psum = pressures.sum()
        println("Min P=%.1f, max P=%.1f, mean P=%.1f Pa".format(pmin, pmax, psum / pressures.size))
    }
}