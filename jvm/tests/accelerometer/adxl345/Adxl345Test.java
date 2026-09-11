///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.accelerometer.Adxl345Minimal;
import it.uhde.periph.chips.accelerometer.Adxl345Full;

public class Adxl345Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(System.getenv().getOrDefault("I2C_ADDR", "0x53").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var accel = new Adxl345Minimal(connection);
            checkTrue(true, "construct_minimal");

            double[] xyz = accel.read();
            checkTrue(Double.isFinite(xyz[0]) && Double.isFinite(xyz[1]) && Double.isFinite(xyz[2]),
                    "read_returns_floats");
            double mag = Math.sqrt(xyz[0] * xyz[0] + xyz[1] * xyz[1] + xyz[2] * xyz[2]);
            checkTrue(mag >= 0.5 && mag <= 1.5, "magnitude_near_1g");

            var accelFull = new Adxl345Full(connection);
            checkTrue(true, "construct_full");
            accelFull.setRange(4);
            xyz = accelFull.read();
            checkTrue(Double.isFinite(xyz[0]) && Double.isFinite(xyz[1]) && Double.isFinite(xyz[2]),
                    "read_after_set_range_4g");
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        if (failed > 0) System.exit(1);
    }
}