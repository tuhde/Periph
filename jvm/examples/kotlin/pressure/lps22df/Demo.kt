///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Lps22dfFull

fun main() {
    I2CConnection(1, 0x5C).use { connection ->                 // open I²C bus 1, device 0x5C, (bus, address=0x5C) → I2CConnection
        // --- Indoor altimeter preset: 25 Hz, 4-sample average, low-pass filter ---
        val lps = Lps22dfFull(connection)                          // construct driver, verifies chip ID and applies defaults, (connection) → Lps22dfFull
        lps.configure(4, 0, true, 1, true)                       // configure chip, (odr=25 Hz, avg=4, enLpfp=true, lfpfCfg=ODR/9, bdu=true) → Unit

        // --- Baseline capture: 2-second stabilization then zero the altimeter ---
        Thread.sleep(2000)
        val baselineP = lps.pressure()                              // read pressure, () → Double Pa
        println("Baseline: %.0f Pa".format(baselineP))

        val pressures = DoubleArray(30)
        val temps = DoubleArray(30)
        val deltas = DoubleArray(30)
        for (n in 0 until 30) {
            val p = lps.pressure()                                    // read pressure, () → Double Pa
            val t = lps.temperature()                                // read temperature, () → Double °C
            val d = lps.altitude(baselineP)                         // compute altitude, (seaLevelPa=baselineP) → Double m
            pressures[n] = p; temps[n] = t; deltas[n] = d
            println("%ds: %.0f Pa, T=%.2f C, Δalt=%.3f m".format(n, p, t, d))
            Thread.sleep(1000)
        }
        val pMin = pressures.min(); val pMax = pressures.max(); val pSum = pressures.sum()
        val tMin = temps.min(); val tMax = temps.max(); val tSum = temps.sum()
        val dMin = deltas.min(); val dMax = deltas.max(); val dSum = deltas.sum()
        println("P min=%.0f max=%.0f mean=%.1f Pa".format(pMin, pMax, pSum / 30))
        println("T min=%.2f max=%.2f mean=%.2f C".format(tMin, tMax, tSum / 30))
        println("Δalt min=%.3f max=%.3f mean=%.3f m".format(dMin, dMax, dSum / 30))
    }
}