///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT
//DEPS com.pi4j:pi4j-core:2.7.0
//DEPS com.pi4j:pi4j-plugin-raspberrypi:2.7.0
//DEPS com.pi4j:pi4j-plugin-linuxfs:2.7.0

import com.pi4j.Pi4J;
import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.power.Ina219Full;

/**
 * Power-supply sanity check: poll the INA219 once per second for 10 seconds,
 * printing bus voltage, current, and power. A prompt between samples 4 and 5
 * lets the user switch on the load mid-run. After the run, min/max/mean of
 * each measured quantity are reported.
 */
public class Demo {

    private static final int    SAMPLES   = 10;
    private static final long   PERIOD_MS = 1000;

    public static void main(String[] args) throws Exception {
        var pi4j = Pi4J.newAutoContext();                              // initialise Pi4J, () → Context
        try (var transport = new I2CTransport(pi4j, 1, 0x40)) {      // open I²C bus 1, device 0x40, (bus, address) → I2CTransport
            var ina = new Ina219Full(transport, 0.1, 2.0);            // construct driver, (transport, rShunt=0.1 Ω, maxCurrent=2.0 A) → Ina219Full

            // --- Configure for noise-sensitive power rail monitoring ---
            // 128-sample averaging suppresses switching noise on a noisy 5 V rail;
            // continuous mode avoids re-triggering overhead between measurements.
            ina.configure(Ina219Full.BRNG_32V, Ina219Full.PGA_8,
                          Ina219Full.ADC_AVG_128, Ina219Full.ADC_AVG_128,
                          Ina219Full.MODE_SHUNT_BUS_CONT);            // configure ADC, (brng, pga, badc, sadc, mode) → void

            double[] voltages  = new double[SAMPLES];
            double[] currents  = new double[SAMPLES];
            double[] powers    = new double[SAMPLES];

            System.out.println("=== INA219 power-supply sanity check ===");
            System.out.printf("%-4s  %-10s  %-10s  %-10s%n", "#", "V_bus (V)", "I (A)", "P (W)");

            // --- Collect 10 one-second samples ---
            // Samples 1–4 are taken with the load in its initial state.
            // After sample 4 the user is prompted to switch the load on,
            // making the current step visible in the data.
            for (int n = 0; n < SAMPLES; n++) {
                if (n == 4) {
                    System.out.println("Switch on load now...");
                }

                double v = ina.voltage();    // read bus voltage, () → double V
                double i = ina.current();    // read current, () → double A
                double p = ina.power();      // read power, () → double W

                voltages[n] = v;
                currents[n] = i;
                powers[n]   = p;

                System.out.printf("%-4d  %-10.3f  %-10.4f  %-10.4f%n", n + 1, v, i, p);
                Thread.sleep(PERIOD_MS);
            }

            // --- Compute and print summary statistics ---
            // Min/max/mean give a quick overview of load stability and
            // reveal any voltage sag under load.
            System.out.println("\n=== Summary ===");
            System.out.printf("%-12s  %-10s  %-10s  %-10s%n", "", "V_bus (V)", "I (A)", "P (W)");
            System.out.printf("%-12s  %-10.3f  %-10.4f  %-10.4f%n",
                    "min", min(voltages), min(currents), min(powers));
            System.out.printf("%-12s  %-10.3f  %-10.4f  %-10.4f%n",
                    "max", max(voltages), max(currents), max(powers));
            System.out.printf("%-12s  %-10.3f  %-10.4f  %-10.4f%n",
                    "mean", mean(voltages), mean(currents), mean(powers));

        } finally {
            pi4j.shutdown();
        }
    }

    private static double min(double[] a) {
        double m = a[0];
        for (double x : a) if (x < m) m = x;
        return m;
    }

    private static double max(double[] a) {
        double m = a[0];
        for (double x : a) if (x > m) m = x;
        return m;
    }

    private static double mean(double[] a) {
        double s = 0;
        for (double x : a) s += x;
        return s / a.length;
    }
}
