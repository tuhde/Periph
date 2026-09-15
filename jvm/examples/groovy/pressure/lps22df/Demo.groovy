///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-groovy:1.1.0

import it.uhde.periph.connection.I2CConnection
import it.uhde.periph.chips.pressure.Lps22dfFull

def connection = new I2CConnection(1, 0x5C)            // open I²C bus 1, device 0x5C, (bus, address=0x5C) → I2CConnection
try {
    // --- Indoor altimeter preset: 25 Hz, 4-sample average, low-pass filter ---
    def lps = new Lps22dfFull(connection)                     // construct driver, verifies chip ID and applies defaults, (connection) → Lps22dfFull
    lps.configure(4, 0, true, 1, true)                       // configure chip, (odr=25 Hz, avg=4, enLpfp=true, lfpfCfg=ODR/9, bdu=true) → void

    // --- Baseline capture: 2-second stabilization then zero the altimeter ---
    Thread.sleep(2000)
    double baselineP = lps.pressure()                          // read pressure, () → double Pa
    printf("Baseline: %.0f Pa%n", baselineP)

    double[] pressures = new double[30]
    double[] temps = new double[30]
    double[] deltas = new double[30]
    30.times { n ->
        double p = lps.pressure()                              // read pressure, () → double Pa
        double t = lps.temperature()                           // read temperature, () → double °C
        double d = lps.altitude(baselineP)                    // compute altitude, (seaLevelPa=baselineP) → double m
        pressures[n] = p; temps[n] = t; deltas[n] = d
        printf("%ds: %.0f Pa, T=%.2f C, Δalt=%.3f m%n", n, p, t, d)
        Thread.sleep(1000)
    }
    double pMin = pressures.min(); double pMax = pressures.max(); double pSum = pressures.sum()
    double tMin = temps.min(); double tMax = temps.max(); double tSum = temps.sum()
    double dMin = deltas.min(); double dMax = deltas.max(); double dSum = deltas.sum()
    printf("P min=%.0f max=%.0f mean=%.1f Pa%n", pMin, pMax, pSum / 30)
    printf("T min=%.2f max=%.2f mean=%.2f C%n", tMin, tMax, tSum / 30)
    printf("Δalt min=%.3f max=%.3f mean=%.3f m%n", dMin, dMax, dSum / 30)
} finally {
    connection.close()
}