///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.pressure.Bmp280Full

/**
 * Pocket altimeter: 60-second run at 1 Hz with IIR filter for smooth readings.
 *
 * The first reading establishes the sea-level reference so altitude starts at 0.
 * Each subsequent reading reports temperature, pressure, altitude, and vertical
 * displacement (in cm) relative to the reference. After the run, prints
 * min/max/mean for all three quantities.
 */
fun main() {
    val samples    = 60
    val intervalMs = 1000L

    I2CTransport(1, 0x76).use { transport ->             // open I²C bus 1, device 0x76 (SDO low), (bus, address=0x76) → I2CTransport
        val sensor = Bmp280Full(transport)                      // construct driver, verifies chip ID, reads calibration, (transport) → Bmp280Full

        // --- Configure for smooth pressure monitoring with IIR filter ---
        // ×4 oversampling on both channels improves SNR without significantly
        // increasing power draw. IIR filter ×4 smooths step changes caused by
        // door slams or wind gusts, giving stable altitude readings at 1 Hz.
        sensor.configure(                                       // configure oversampling, mode, filter, standby, (osrsT, osrsP, mode, filter, tSb) → Unit
            Bmp280Full.OSRS_X4,
            Bmp280Full.OSRS_X4,
            Bmp280Full.MODE_FORCED,
            Bmp280Full.FILTER_4,
            Bmp280Full.T_SB_0_5_MS)

        // --- Take first reading to establish the reference altitude ---
        // The sea-level pressure derived from this reading anchors altitude = 0.
        // Any subsequent change in pressure appears as vertical displacement.
        val firstPressure = sensor.pressure()                   // read pressure for reference, () → Double hPa
        val seaLevel      = sensor.seaLevelPressure(0.0)       // derive sea-level pressure at altitude = 0, (altitudeM=0.0 m) → Double hPa
        val firstTemp     = sensor.temperature()                // read temperature for reference, () → Double °C
        val firstAlt      = sensor.altitude(seaLevel)          // compute altitude (should be ~0), (seaLevelHpa) → Double m

        println("Reference: temperature=%.2f °C  pressure=%.2f hPa  altitude=%.1f m  (sea-level ref=%.2f hPa)"
            .format(firstTemp, firstPressure, firstAlt, seaLevel))

        val temps     = DoubleArray(samples)
        val pressures = DoubleArray(samples)
        val altitudes = DoubleArray(samples)

        // --- 60-second sampling loop ---
        // Each iteration reads temperature, pressure, and altitude; prints the
        // current values plus vertical displacement from the reference position.
        for (i in 0 until samples) {
            val t   = sensor.temperature()                      // read temperature, () → Double °C
            val p   = sensor.pressure()                         // read pressure, () → Double hPa
            val alt = sensor.altitude(seaLevel)                 // compute altitude relative to reference, (seaLevelHpa) → Double m
            val dAlt = alt - firstAlt

            temps[i]     = t
            pressures[i] = p
            altitudes[i] = alt

            val direction = when {
                dAlt >  0.005 -> "up"
                dAlt < -0.005 -> "down"
                else          -> "level"
            }
            println("[%2d] temperature=%.2f °C  pressure=%.2f hPa  altitude=%.1f m  moved %s %.0f cm"
                .format(i + 1, t, p, alt, direction, Math.abs(dAlt) * 100))

            Thread.sleep(intervalMs)
        }

        // --- Print summary statistics ---
        // After the run, report the full range and mean for each quantity so
        // the user can see how much the environment changed during the session.
        val minT = temps.min();     val maxT = temps.max();     val sumT = temps.sum()
        val minP = pressures.min(); val maxP = pressures.max(); val sumP = pressures.sum()
        val minA = altitudes.min(); val maxA = altitudes.max(); val sumA = altitudes.sum()
        println("\n--- Summary ($samples samples) ---")
        println("Temperature: min=%.2f °C  max=%.2f °C  mean=%.2f °C".format(minT, maxT, sumT / samples))
        println("Pressure:    min=%.2f hPa  max=%.2f hPa  mean=%.2f hPa".format(minP, maxP, sumP / samples))
        println("Altitude:    min=%.1f m  max=%.1f m  mean=%.1f m".format(minA, maxA, sumA / samples))
    }
}
