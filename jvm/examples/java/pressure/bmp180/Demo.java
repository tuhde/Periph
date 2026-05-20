///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.pressure.Bmp180Full;

/**
 * Pocket altimeter: 60-second run at 1 Hz in ULP mode.
 *
 * The first reading establishes the sea-level reference so altitude starts at 0.
 * Each subsequent reading reports temperature, pressure, altitude, and vertical
 * displacement (in cm) relative to the reference. After the run, prints
 * min/max/mean for all three quantities.
 */
public class Demo {

    private static final int    SAMPLES     = 60;
    private static final long   INTERVAL_MS = 1000;

    public static void main(String[] args) throws Exception {
        try (var transport = new I2CTransport(1, 0x77)) {       // open I²C bus 1, device 0x77 (fixed address), (bus, address=0x77) → I2CTransport
            var sensor = new Bmp180Full(transport);                     // construct driver, verifies chip ID, reads calibration, (transport) → Bmp180Full

            // --- Configure for low-power continuous monitoring ---
            // ULP mode (OSS = 0) minimises power draw (~3 µA RMS) and conversion time
            // (~4.5 ms). For a pocket altimeter taking one sample per second the
            // extra resolution of higher OSS is not needed; ULP resolves ~1 m.
            sensor.setOversampling(Bmp180Full.OSS_ULP);                // set ultra-low-power mode, (oss=0–3) → void

            // --- Take first reading to establish reference altitude ---
            // The sea-level pressure derived from this reading anchors altitude = 0.
            // Any subsequent change in pressure will appear as vertical displacement.
            double firstPressure = sensor.pressure();                   // read pressure for reference, () → double hPa
            double seaLevel      = sensor.seaLevelPressure(0.0);       // derive sea-level pressure at altitude = 0, (altitudeM=0.0 m) → double hPa
            double firstTemp     = sensor.temperature();                // read temperature for reference, () → double °C
            double firstAlt      = sensor.altitude(seaLevel);          // compute altitude (should be ~0), (seaLevelHpa) → double m

            System.out.printf("Reference: temperature=%.2f °C  pressure=%.2f hPa  altitude=%.1f m  (sea-level ref=%.2f hPa)%n",
                    firstTemp, firstPressure, firstAlt, seaLevel);

            double[] temps     = new double[SAMPLES];
            double[] pressures = new double[SAMPLES];
            double[] altitudes = new double[SAMPLES];

            // --- 60-second sampling loop ---
            // Each iteration reads temperature, pressure, and altitude; prints the
            // current values plus vertical displacement from the reference position.
            for (int i = 0; i < SAMPLES; i++) {
                double t   = sensor.temperature();                      // read temperature, () → double °C
                double p   = sensor.pressure();                         // read pressure, () → double hPa
                double alt = sensor.altitude(seaLevel);                 // compute altitude relative to reference, (seaLevelHpa) → double m
                double dAlt = alt - firstAlt;

                temps[i]     = t;
                pressures[i] = p;
                altitudes[i] = alt;

                String direction = dAlt > 0.005 ? "up" : dAlt < -0.005 ? "down" : "level";
                System.out.printf("[%2d] temperature=%.2f °C  pressure=%.2f hPa  altitude=%.1f m  moved %s %.0f cm%n",
                        i + 1, t, p, alt, direction, Math.abs(dAlt) * 100);

                Thread.sleep(INTERVAL_MS);
            }

            // --- Print summary statistics ---
            // After the run, report the full range and mean for each quantity so
            // the user can see how much the environment changed during the session.
            double minT = temps[0], maxT = temps[0], sumT = 0;
            double minP = pressures[0], maxP = pressures[0], sumP = 0;
            double minA = altitudes[0], maxA = altitudes[0], sumA = 0;
            for (int i = 0; i < SAMPLES; i++) {
                minT = Math.min(minT, temps[i]);     maxT = Math.max(maxT, temps[i]);     sumT += temps[i];
                minP = Math.min(minP, pressures[i]); maxP = Math.max(maxP, pressures[i]); sumP += pressures[i];
                minA = Math.min(minA, altitudes[i]); maxA = Math.max(maxA, altitudes[i]); sumA += altitudes[i];
            }
            System.out.printf("%n--- Summary (%d samples) ---%n", SAMPLES);
            System.out.printf("Temperature: min=%.2f °C  max=%.2f °C  mean=%.2f °C%n",
                    minT, maxT, sumT / SAMPLES);
            System.out.printf("Pressure:    min=%.2f hPa  max=%.2f hPa  mean=%.2f hPa%n",
                    minP, maxP, sumP / SAMPLES);
            System.out.printf("Altitude:    min=%.1f m  max=%.1f m  mean=%.1f m%n",
                    minA, maxA, sumA / SAMPLES);

        }
    }
}
