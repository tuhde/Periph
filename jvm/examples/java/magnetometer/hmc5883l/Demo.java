///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.magnetometer.Hmc5883lFull;

/**
 * Electronic compass demo: reads magnetic field from HMC5883L at 15 Hz,
 * computes heading from X/Y axes using atan2(y, x), prints compass bearing (0–360°),
 * and warns if sensor is held vertically (|Z| > 30 µT) indicating tilt compensation needed.
 */
public class Demo {

    private static final int SAMPLES = 10;
    private static final long INTERVAL_MS = 500;

    public static void main(String[] args) throws Exception {
        try (var connection = new I2CConnection(1, 0x1E)) {            // open I²C bus 1, device 0x1E, (bus, address) → I2CConnection
            var hmc5883l = new Hmc5883lFull(connection);                    // construct driver, (connection) → Hmc5883lFull

            // --- Configure for electronic compass ---
            // 8-sample averaging at 15 Hz suppresses noise; ±1.3 Ga gain covers Earth's field (~0.5 Ga).
            hmc5883l.configure(15, 8, 1);                                 // configure chip, (odr 0.75-75 Hz, averaging 1/2/4/8, gain 0-7) → void

            System.out.println("Electronic compass demo — hold sensor flat, rotate horizontally");
            System.out.println("Vertical mount warning: |Z| > 30 µT indicates tilt compensation needed");
            System.out.println();

            // --- Sample and compute heading ---
            // User rotates the sensor horizontally; we compute heading from X/Y axes.
            // At n=5, user is prompted to tilt vertically to demonstrate Z-axis detection.
            for (int n = 0; n < SAMPLES; n++) {
                while (!hmc5883l.dataReady()) {                           // check data ready, () → boolean
                    Thread.sleep(1);
                }
                Double[] field = hmc5883l.magneticField();                // read magnetic field, () → Double[3] T

                Double x = field[0];
                Double y = field[1];
                Double z = field[2];

                // --- Compute heading from X and Y ---
                if (x != null && y != null) {
                    double heading = Math.toDegrees(Math.atan2(y, x));
                    if (heading < 0) heading += 360;
                    System.out.printf("Heading: %.1f°%n", heading);
                }

                // --- Vertical mount detection ---
                if (z != null && Math.abs(z) > 30e-6) {
                    System.out.printf("[TILT WARNING] Z=%.1f µT — tilt compensation needed%n", z * 1e6);
                }

                if (n == 4) {
                    System.out.println(">>> Now tilt sensor vertically <<<");
                }

                Thread.sleep(INTERVAL_MS);
            }
        }
    }
}