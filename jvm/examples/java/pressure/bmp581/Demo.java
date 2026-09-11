///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.Bmp581Full;

public class Demo {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x46)) {       // open I²C bus 1, device 0x46, (bus, address=0x46) → I2CConnection

            // --- Precision altimeter: 10 Hz NORMAL mode for 30 seconds ---
            var bmp = new Bmp581Full(connection);                      // construct driver, (connection) → Bmp581Full
            bmp.configure(0x17, Bmp581Full.OSR_16X, Bmp581Full.OSR_4X, true); // configure, (odr=10Hz, osr_p=×16, osr_t=×4, press_en) → void

            double[] pressures = new double[300];
            double[] temps = new double[300];
            double[] alts = new double[300];
            for (int n = 0; n < 300; n++) {
                pressures[n] = bmp.pressure();                         // read pressure, () → double Pa
                temps[n] = bmp.temperature();                          // read temperature, () → double °C
                alts[n] = bmp.altitude();                             // compute altitude, (seaLevelPa=101325.0) → double
                if (n % 10 == 0) {
                    int start = Math.max(0, n - 10);
                    int span = Math.min(10, n);
                    if (span > 0) {
                        double mp = 0, mt = 0, ma = 0;
                        for (int k = start; k < n; k++) {
                            mp += pressures[k];
                            mt += temps[k];
                            ma += alts[k];
                        }
                        mp /= span; mt /= span; ma /= span;
                        System.out.printf("%d0s: rolling P=%.1f Pa  T=%.2f C  alt=%.2f m%n", n / 10, mp, mt, ma);
                    }
                }
                Thread.sleep(100);
            }

            double amin = alts[0], amax = alts[0];
            for (double a : alts) {
                if (a < amin) amin = a;
                if (a > amax) amax = a;
            }
            System.out.printf("Bypass: alt min=%.3f max=%.3f spread=%.3f m%n", amin, amax, amax - amin);

            // --- Compare IIR bypass vs IIR coefficient 3 noise floor ---
            bmp.setIirFilter(Bmp581Full.IIR_COEFF_3, Bmp581Full.IIR_BYPASS); // set IIR filter, (coeff_p=7-tap, coeff_t=bypass) → void

            double[] alts2 = new double[300];
            for (int n = 0; n < 300; n++) {
                bmp.pressure();                                         // read pressure, () → double Pa
                alts2[n] = bmp.altitude();                              // compute altitude, (seaLevelPa=101325.0) → double
                Thread.sleep(100);
            }
            double amin2 = alts2[0], amax2 = alts2[0];
            for (double a : alts2) {
                if (a < amin2) amin2 = a;
                if (a > amax2) amax2 = a;
            }
            System.out.printf("IIR=3:  alt min=%.3f max=%.3f spread=%.3f m%n", amin2, amax2, amax2 - amin2);

            double pmin = pressures[0], pmax = pressures[0], psum = 0;
            for (double p : pressures) {
                if (p < pmin) pmin = p;
                if (p > pmax) pmax = p;
                psum += p;
            }
            System.out.printf("Min P=%.1f, max P=%.1f, mean P=%.1f Pa%n", pmin, pmax, psum / pressures.length);
        }
    }
}