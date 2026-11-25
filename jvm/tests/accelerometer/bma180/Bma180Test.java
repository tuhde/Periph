///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.accelerometer.Bma180Minimal;
import it.uhde.periph.chips.accelerometer.Bma180Full;

public class Bma180Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x40").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var chip = new Bma180Minimal(connection);
            checkTrue("construct_minimal", true);

            double[] xyz = chip.read();
            checkTrue("read_returns_doubles", xyz.length == 3);
            double mag = Math.sqrt(xyz[0]*xyz[0] + xyz[1]*xyz[1] + xyz[2]*xyz[2]);
            checkTrue("magnitude_near_1g", mag >= 0.5 && mag <= 1.5);

            var chipFull = new Bma180Full(connection);
            checkTrue("construct_full", true);
            chipFull.setRange(4);
            double[] xyz2 = chipFull.read();
            checkTrue("read_after_set_range_4g", xyz2.length == 3);

            double temp = chipFull.readTemperature();
            checkTrue("temperature_in_range", temp >= -40.0 && temp <= 87.5);
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
