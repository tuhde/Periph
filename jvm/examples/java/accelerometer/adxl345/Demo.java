///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.accelerometer.Adxl345Minimal;

public class Demo {
    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x53").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var accel = new Adxl345Minimal(connection);                  // construct driver and verify DEVID, (connection) → Adxl345Minimal

            // --- 50-sample stationary tilt characterization at 10 Hz ---
            // With the sensor flat and the Z axis up, gravity should project entirely
            // onto Z. Tilting the board visibly redistributes the 1 *g* magnitude
            // across X and Y; the total vector magnitude stays near 1 *g*.
            final int SAMPLES = 50;
            double magMin = Double.POSITIVE_INFINITY;
            double magMax = Double.NEGATIVE_INFINITY;

            for (int n = 0; n < SAMPLES; n++) {
                double[] xyz = accel.read();                              // Read 3-axis acceleration, () → double[3] g, g, g
                double mag = Math.sqrt(xyz[0] * xyz[0] + xyz[1] * xyz[1] + xyz[2] * xyz[2]);
                if (mag < magMin) magMin = mag;
                if (mag > magMax) magMax = mag;
                System.out.printf("%2d  x=%+.3f  y=%+.3f  z=%+.3f  |a|=%.3f g%n",
                        n, xyz[0], xyz[1], xyz[2], mag);
                Thread.sleep(100);
            }

            System.out.printf("min |a|=%.3f g  max |a|=%.3f g%n", magMin, magMax);
        }
    }
}