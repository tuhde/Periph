///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-groovy:1.2.1

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

private static final int SAMPLES = 60
private static final long INTERVAL_MS = 1000

def conn = new I2CConnection(1, 0x77)
try {
    def sensor = new Bmp085Full(conn)

    // --- Configure for low-power continuous monitoring ---
    // ULP mode (OSS = 0) minimises power draw (~3 µA RMS) and conversion time
    // (~4.5 ms). For a pocket altimeter taking one sample per second the
    // extra resolution of higher OSS is not needed; ULP resolves ~1 m.
    sensor.setOversampling(Bmp085Full.OSS_ULP)

    // --- Take first reading to establish reference altitude ---
    // The sea-level pressure derived from this reading anchors altitude = 0.
    // Any subsequent change in pressure will appear as vertical displacement.
    double firstPressure = sensor.pressure()
    double seaLevel = sensor.seaLevelPressure(0.0)
    double firstTemp = sensor.temperature()
    double firstAlt = sensor.altitude(seaLevel)

    printf "Reference: temperature=%.2f °C  pressure=%.2f Pa  altitude=%.1f m  (sea-level ref=%.2f Pa)%n",
            firstTemp, firstPressure, firstAlt, seaLevel

    double[] temps = new double[SAMPLES]
    double[] pressures = new double[SAMPLES]
    double[] altitudes = new double[SAMPLES]

    // --- 60-second sampling loop ---
    // Each iteration reads temperature, pressure, and altitude; prints the
    // current values plus vertical displacement from the reference position.
    for (int i = 0; i < SAMPLES; i++) {
        double t = sensor.temperature()
        double p = sensor.pressure()
        double alt = sensor.altitude(seaLevel)
        double dAlt = alt - firstAlt

        temps[i] = t
        pressures[i] = p
        altitudes[i] = alt

        String direction = dAlt > 0.005 ? "up" : dAlt < -0.005 ? "down" : "level"
        printf "[%2d] temperature=%.2f °C  pressure=%.2f Pa  altitude=%.1f m  moved %s %.0f cm%n",
                i + 1, t, p, alt, direction, Math.abs(dAlt) * 100

        Thread.sleep(INTERVAL_MS)
    }

    // --- Print summary statistics ---
    // After the run, report the full range and mean for each quantity so
    // the user can see how much the environment changed during the session.
    double minT = temps[0], maxT = temps[0], sumT = 0
    double minP = pressures[0], maxP = pressures[0], sumP = 0
    double minA = altitudes[0], maxA = altitudes[0], sumA = 0
    for (int i = 0; i < SAMPLES; i++) {
        minT = Math.min(minT, temps[i]); maxT = Math.max(maxT, temps[i]); sumT += temps[i]
        minP = Math.min(minP, pressures[i]); maxP = Math.max(maxP, pressures[i]); sumP += pressures[i]
        minA = Math.min(minA, altitudes[i]); maxA = Math.max(maxA, altitudes[i]); sumA += altitudes[i]
    }
    printf "%n--- Summary (%d samples) ---%n", SAMPLES
    printf "Temperature: min=%.2f °C  max=%.2f °C  mean=%.2f °C%n",
            minT, maxT, sumT / SAMPLES
    printf "Pressure:    min=%.2f Pa  max=%.2f Pa  mean=%.2f Pa%n",
            minP, maxP, sumP / SAMPLES
    printf "Altitude:    min=%.1f m  max=%.1f m  mean=%.1f m%n",
            minA, maxA, sumA / SAMPLES
} finally {
    conn.close()
}