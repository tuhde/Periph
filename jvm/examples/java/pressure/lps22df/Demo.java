///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.Lps22dfFull;

public class Demo {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x5C)) {      // open I²C bus 1, device 0x5C, (bus, address=0x5C) → I2CConnection

            // --- Indoor altimeter preset: 25 Hz, 4-sample average, low-pass filter ---
            // Low-pass at ODR/9 smooths short-term pressure noise (door slams, fans);
            // 4-sample averaging trims noise without adding visible lag.
            var lps = new Lps22dfFull(connection);                     // construct driver, verifies chip ID and applies defaults, (connection) → Lps22dfFull
            lps.configure(4, 0, true, 1, true);                       // configure chip, (odr=25 Hz, avg=4, enLpfp=true, lfpfCfg=ODR/9, bdu=true) → void

            // --- Baseline capture: 2-second stabilization then zero the altimeter ---
            Thread.sleep(2000);
            double baselineP = lps.pressure();                          // read pressure, () → double Pa
            System.out.printf("Baseline: %.0f Pa%n", baselineP);

            double[] pressures = new double[30];
            double[] temps = new double[30];
            double[] deltas = new double[30];
            for (int n = 0; n < 30; n++) {
                double p = lps.pressure();                              // read pressure, () → double Pa
                double t = lps.temperature();                           // read temperature, () → double °C
                double d = lps.altitude(baselineP);                    // compute altitude, (seaLevelPa=baselineP) → double m
                                                                                // delta altitude in metres from the baseline
                pressures[n] = p;
                temps[n] = t;
                deltas[n] = d;
                System.out.printf("%ds: %.0f Pa, T=%.2f C, Δalt=%.3f m%n", n, p, t, d);
                Thread.sleep(1000);
            }
            double pMin = pressures[0], pMax = pressures[0], pSum = 0;
            double tMin = temps[0], tMax = temps[0], tSum = 0;
            double dMin = deltas[0], dMax = deltas[0], dSum = 0;
            for (int i = 0; i < 30; i++) {
                if (pressures[i] < pMin) pMin = pressures[i];
                if (pressures[i] > pMax) pMax = pressures[i];
                pSum += pressures[i];
                if (temps[i] < tMin) tMin = temps[i];
                if (temps[i] > tMax) tMax = temps[i];
                tSum += temps[i];
                if (deltas[i] < dMin) dMin = deltas[i];
                if (deltas[i] > dMax) dMax = deltas[i];
                dSum += deltas[i];
            }
            System.out.printf("P min=%.0f max=%.0f mean=%.1f Pa%n", pMin, pMax, pSum / 30);
            System.out.printf("T min=%.2f max=%.2f mean=%.2f C%n", tMin, tMax, tSum / 30);
            System.out.printf("Δalt min=%.3f max=%.3f mean=%.3f m%n", dMin, dMax, dSum / 30);
        }
    }
}