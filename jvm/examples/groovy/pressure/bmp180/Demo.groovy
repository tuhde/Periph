///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.pressure.Bmp180Full

/**
 * Pocket altimeter: 60-second run at 1 Hz in ULP mode.
 *
 * The first reading establishes the sea-level reference so altitude starts at 0.
 * Each subsequent reading reports temperature, pressure, altitude, and vertical
 * displacement (in cm) relative to the reference. After the run, prints
 * min/max/mean for all three quantities.
 */

final SAMPLES     = 60
final INTERVAL_MS = 1000L

def transport = new I2CTransport(1, 0x77)           // open I²C bus 1, device 0x77 (fixed address), (bus, address=0x77) → I2CTransport
try {
    def sensor = new Bmp180Full(transport)                 // construct driver, verifies chip ID, reads calibration, (transport) → Bmp180Full

    // --- Configure for low-power continuous monitoring ---
    // ULP mode (OSS = 0) minimises power draw (~3 µA RMS) and conversion time
    // (~4.5 ms). For a pocket altimeter taking one sample per second the
    // extra resolution of higher OSS is not needed; ULP resolves ~1 m.
    sensor.setOversampling(Bmp180Full.OSS_ULP)             // set ultra-low-power mode, (oss=0–3) → void

    // --- Take first reading to establish reference altitude ---
    // The sea-level pressure derived from this reading anchors altitude = 0.
    // Any subsequent change in pressure will appear as vertical displacement.
    double firstPressure = sensor.pressure()               // read pressure for reference, () → double hPa
    double seaLevel      = sensor.seaLevelPressure(0.0)   // derive sea-level pressure at altitude = 0, (altitudeM=0.0 m) → double hPa
    double firstTemp     = sensor.temperature()            // read temperature for reference, () → double °C
    double firstAlt      = sensor.altitude(seaLevel)      // compute altitude (should be ~0), (seaLevelHpa) → double m

    printf("Reference: temperature=%.2f °C  pressure=%.2f hPa  altitude=%.1f m  (sea-level ref=%.2f hPa)%n",
           firstTemp, firstPressure, firstAlt, seaLevel)

    double[] temps     = new double[SAMPLES]
    double[] pressures = new double[SAMPLES]
    double[] altitudes = new double[SAMPLES]

    // --- 60-second sampling loop ---
    // Each iteration reads temperature, pressure, and altitude; prints the
    // current values plus vertical displacement from the reference position.
    (0..<SAMPLES).each { int i ->
        double t   = sensor.temperature()                  // read temperature, () → double °C
        double p   = sensor.pressure()                     // read pressure, () → double hPa
        double alt = sensor.altitude(seaLevel)             // compute altitude relative to reference, (seaLevelHpa) → double m
        double dAlt = alt - firstAlt

        temps[i]     = t
        pressures[i] = p
        altitudes[i] = alt

        String direction = dAlt > 0.005 ? 'up' : dAlt < -0.005 ? 'down' : 'level'
        printf("[%2d] temperature=%.2f °C  pressure=%.2f hPa  altitude=%.1f m  moved %s %.0f cm%n",
               i + 1, t, p, alt, direction, Math.abs(dAlt) * 100)

        Thread.sleep(INTERVAL_MS)
    }

    // --- Print summary statistics ---
    // After the run, report the full range and mean for each quantity so
    // the user can see how much the environment changed during the session.
    printf("%n--- Summary (%d samples) ---%n", SAMPLES)
    printf("Temperature: min=%.2f °C  max=%.2f °C  mean=%.2f °C%n",
           temps.min(), temps.max(), temps.sum() / SAMPLES)
    printf("Pressure:    min=%.2f hPa  max=%.2f hPa  mean=%.2f hPa%n",
           pressures.min(), pressures.max(), pressures.sum() / SAMPLES)
    printf("Altitude:    min=%.1f m  max=%.1f m  mean=%.1f m%n",
           altitudes.min(), altitudes.max(), altitudes.sum() / SAMPLES)

} finally {
    transport.close()
}
