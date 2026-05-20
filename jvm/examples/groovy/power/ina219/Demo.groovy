///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-groovy:1.0-SNAPSHOT

import groovy.transform.Field
import it.uhde.periph.transport.I2CTransport
import it.uhde.periph.chips.power.Ina219Full

/**
 * Power-supply sanity check: poll the INA219 once per second for 10 seconds,
 * printing bus voltage, current, and power. A prompt between samples 4 and 5
 * lets the user switch on the load mid-run. After the run, min/max/mean of
 * each measured quantity are reported.
 */

@Field final int  SAMPLES   = 10
@Field final long PERIOD_MS = 1000L

def transport = new I2CTransport(1, 0x40)               // open I²C bus 1, device 0x40, (bus, address) → I2CTransport
try {
    def ina = new Ina219Full(transport, 0.1, 2.0)              // construct driver, (transport, rShunt=0.1 Ω, maxCurrent=2.0 A) → Ina219Full

    // --- Configure for noise-sensitive power rail monitoring ---
    // 128-sample averaging suppresses switching noise on a noisy 5 V rail;
    // continuous mode avoids re-triggering overhead between measurements.
    ina.configure(Ina219Full.BRNG_32V, Ina219Full.PGA_8,
                  Ina219Full.ADC_AVG_128, Ina219Full.ADC_AVG_128,
                  Ina219Full.MODE_SHUNT_BUS_CONT)              // configure ADC, (brng, pga, badc, sadc, mode) → void

    double[] voltages = new double[SAMPLES]
    double[] currents = new double[SAMPLES]
    double[] powers   = new double[SAMPLES]

    println("=== INA219 power-supply sanity check ===")
    printf("%-4s  %-10s  %-10s  %-10s%n", "#", "V_bus (V)", "I (A)", "P (W)")

    // --- Collect 10 one-second samples ---
    // Samples 1–4 are taken with the load in its initial state.
    // After sample 4 the user is prompted to switch the load on,
    // making the current step visible in the data.
    (0 until SAMPLES).each { int n ->
        if (n == 4) {
            println("Switch on load now...")
        }

        double v = ina.voltage()   // read bus voltage, () → double V
        double i = ina.current()   // read current, () → double A
        double p = ina.power()     // read power, () → double W

        voltages[n] = v
        currents[n] = i
        powers[n]   = p

        printf("%-4d  %-10.3f  %-10.4f  %-10.4f%n", n + 1, v, i, p)
        Thread.sleep(PERIOD_MS)
    }

    // --- Compute and print summary statistics ---
    // Min/max/mean give a quick overview of load stability and
    // reveal any voltage sag under load.
    println("\n=== Summary ===")
    printf("%-12s  %-10s  %-10s  %-10s%n", "", "V_bus (V)", "I (A)", "P (W)")
    printf("%-12s  %-10.3f  %-10.4f  %-10.4f%n",
           "min", voltages.min(), currents.min(), powers.min())
    printf("%-12s  %-10.3f  %-10.4f  %-10.4f%n",
           "max", voltages.max(), currents.max(), powers.max())
    printf("%-12s  %-10.3f  %-10.4f  %-10.4f%n",
           "mean", voltages.sum() / SAMPLES, currents.sum() / SAMPLES, powers.sum() / SAMPLES)

} finally {
    transport.close()
}
