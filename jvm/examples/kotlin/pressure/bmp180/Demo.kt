///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-kotlin:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.pressure.Bmp180Full
import kotlin.math.abs

/**
 * Pocket altimeter: 60-second run at 1 Hz in ULP mode.
 *
 * The first reading establishes the sea-level reference so altitude starts at 0.
 * Each subsequent reading reports temperature, pressure, altitude, and vertical
 * displacement (in cm) relative to the reference. After the run, prints
 * min/max/mean for all three quantities.
 */

private const val SAMPLES     = 60
private const val INTERVAL_MS = 1000L

fun main() {
    I2CTransport(1, 0x77).use { transport ->            // open I²C bus 1, device 0x77 (fixed address), (bus, address=0x77) → I2CTransport
        val sensor = Bmp180Full(transport)                     // construct driver, verifies chip ID, reads calibration, (transport) → Bmp180Full

        // --- Configure for low-power continuous monitoring ---
        // ULP mode (OSS = 0) minimises power draw (~3 µA RMS) and conversion time
        // (~4.5 ms). For a pocket altimeter taking one sample per second the
        // extra resolution of higher OSS is not needed; ULP resolves ~1 m.
        sensor.setOversampling(Bmp180Full.OSS_ULP)             // set ultra-low-power mode, (oss=0–3) → Unit

        // --- Take first reading to establish reference altitude ---
        // The sea-level pressure derived from this reading anchors altitude = 0.
        // Any subsequent change in pressure will appear as vertical displacement.
        val firstPressure = sensor.pressure()                  // read pressure for reference, () → Double hPa
        val seaLevel      = sensor.seaLevelPressure(0.0)      // derive sea-level pressure at altitude = 0, (altitudeM=0.0 m) → Double hPa
        val firstTemp     = sensor.temperature()               // read temperature for reference, () → Double °C
        val firstAlt      = sensor.altitude(seaLevel)         // compute altitude (should be ~0), (seaLevelHpa) → Double m

        println("Reference: temperature=%.2f °C  pressure=%.2f hPa  altitude=%.1f m  (sea-level ref=%.2f hPa)"
            .format(firstTemp, firstPressure, firstAlt, seaLevel))

        val temps     = DoubleArray(SAMPLES)
        val pressures = DoubleArray(SAMPLES)
        val altitudes = DoubleArray(SAMPLES)

        // --- 60-second sampling loop ---
        // Each iteration reads temperature, pressure, and altitude; prints the
        // current values plus vertical displacement from the reference position.
        for (i in 0 until SAMPLES) {
            val t   = sensor.temperature()                     // read temperature, () → Double °C
            val p   = sensor.pressure()                        // read pressure, () → Double hPa
            val alt = sensor.altitude(seaLevel)               // compute altitude relative to reference, (seaLevelHpa) → Double m
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
                .format(i + 1, t, p, alt, direction, abs(dAlt) * 100))

            Thread.sleep(INTERVAL_MS)
        }

        // --- Print summary statistics ---
        // After the run, report the full range and mean for each quantity so
        // the user can see how much the environment changed during the session.
        println("\n--- Summary ($SAMPLES samples) ---")
        println("Temperature: min=%.2f °C  max=%.2f °C  mean=%.2f °C"
            .format(temps.min(), temps.max(), temps.average()))
        println("Pressure:    min=%.2f hPa  max=%.2f hPa  mean=%.2f hPa"
            .format(pressures.min(), pressures.max(), pressures.average()))
        println("Altitude:    min=%.1f m  max=%.1f m  mean=%.1f m"
            .format(altitudes.min(), altitudes.max(), altitudes.average()))
    }

}
