///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-kotlin:1.2.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Bmp085Full

/**
 * Pocket altimeter: 60-second run at 1 Hz in ULP mode.
 *
 * The first reading establishes the sea-level reference so altitude starts at 0.
 * Each subsequent reading reports temperature, pressure, altitude, and vertical
 * displacement (in cm) relative to the reference. After the run, prints
 * min/max/mean for all three quantities.
 */

private const val SAMPLES = 60
private const val INTERVAL_MS = 1000L

fun main() {
    I2CConnection(1, 0x77).use { connection ->
        val sensor = Bmp085Full(connection)

        // --- Configure for low-power continuous monitoring ---
        // ULP mode (OSS = 0) minimises power draw (~3 µA RMS) and conversion time
        // (~4.5 ms). For a pocket altimeter taking one sample per second the
        // extra resolution of higher OSS is not needed; ULP resolves ~1 m.
        sensor.setOversampling(Bmp085Full.OSS_ULP)

        // --- Take first reading to establish reference altitude ---
        // The sea-level pressure derived from this reading anchors altitude = 0.
        // Any subsequent change in pressure will appear as vertical displacement.
        val firstPressure = sensor.pressure()
        val seaLevel = sensor.seaLevelPressure(0.0)
        val firstTemp = sensor.temperature()
        val firstAlt = sensor.altitude(seaLevel)

        println("Reference: temperature=${firstTemp:.2f} °C  pressure=${firstPressure:.2f} Pa  altitude=${firstAlt:.1f} m  (sea-level ref=${seaLevel:.2f} Pa)")

        val temps = DoubleArray(SAMPLES)
        val pressures = DoubleArray(SAMPLES)
        val altitudes = DoubleArray(SAMPLES)

        // --- 60-second sampling loop ---
        // Each iteration reads temperature, pressure, and altitude; prints the
        // current values plus vertical displacement from the reference position.
        for (i in 0 until SAMPLES) {
            val t = sensor.temperature()
            val p = sensor.pressure()
            val alt = sensor.altitude(seaLevel)
            val dAlt = alt - firstAlt

            temps[i] = t
            pressures[i] = p
            altitudes[i] = alt

            val direction = when {
                dAlt > 0.005 -> "up"
                dAlt < -0.005 -> "down"
                else -> "level"
            }
            println("[${"%2d".format(i + 1)}] temperature=${t:.2f} °C  pressure=${p:.2f} Pa  altitude=${alt:.1f} m  moved $direction ${Math.abs(dAlt) * 100:.0f} cm")

            Thread.sleep(INTERVAL_MS)
        }

        // --- Print summary statistics ---
        // After the run, report the full range and mean for each quantity so
        // the user can see how much the environment changed during the session.
        var minT = temps[0]; var maxT = temps[0]; var sumT = 0.0
        var minP = pressures[0]; var maxP = pressures[0]; var sumP = 0.0
        var minA = altitudes[0]; var maxA = altitudes[0]; var sumA = 0.0
        for (i in 0 until SAMPLES) {
            minT = minOf(minT, temps[i]); maxT = maxOf(maxT, temps[i]); sumT += temps[i]
            minP = minOf(minP, pressures[i]); maxP = maxOf(maxP, pressures[i]); sumP += pressures[i]
            minA = minOf(minA, altitudes[i]); maxA = maxOf(maxA, altitudes[i]); sumA += altitudes[i]
        }
        println("\n--- Summary ($SAMPLES samples) ---")
        println("Temperature: min=${minT:.2f} °C  max=${maxT:.2f} °C  mean=${sumT / SAMPLES:.2f} °C")
        println("Pressure:    min=${minP:.2f} Pa  max=${maxP:.2f} Pa  mean=${sumP / SAMPLES:.2f} Pa")
        println("Altitude:    min=${minA:.1f} m  max=${maxA:.1f} m  mean=${sumA / SAMPLES:.1f} m")
    }
}