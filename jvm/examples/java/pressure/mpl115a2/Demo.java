///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.0
//DEPS it.uhde:periph-java:1.5.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.MPL115A2Full;

public class Demo {
    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x60)) {       // open I²C bus 1, device 0x60, (bus, address=0x60) → I2CConnection
            var sensor = new MPL115A2Full(connection);           // construct driver, loads the coefficients, (connection) → MPL115A2Full

            double[] k = sensor.coefficients();                  // read cached coefficients, () → double[] {a0, b1, b2, c12}
            System.out.printf("a0=%.3f b1=%.5f b2=%.5f c12=%.8f%n", k[0], k[1], k[2], k[3]);

            // --- Barometer log ---
            // Reads the pressure every 2 s for 30 s, printing Pa, hPa, approximate
            // altitude and the raw counts, then the min/max of the run.
            double pmin = Double.MAX_VALUE, pmax = 0;
            for (int n = 0; n < 15; n++) {
                double pa = sensor.readPressure();               // read pressure, () → double Pa
                double hpa = sensor.pressureHpa();               // read pressure, () → double hPa
                double alt = sensor.altitude();                  // compute altitude, (seaLevelPa=101325) → double m
                int[] raw = sensor.readRaw();                    // one conversion, () → int[] {padc, tadc}
                pmin = Math.min(pmin, pa);
                pmax = Math.max(pmax, pa);
                System.out.printf("%ds: %.0f Pa, %.2f hPa, alt=%.1f m, raw=(%d, %d)%n", n * 2, pa, hpa, alt, raw[0], raw[1]);
                Thread.sleep(2000);
            }
            System.out.printf("Summary: min=%.0f Pa, max=%.0f Pa%n", pmin, pmax);
        }
    }
}
