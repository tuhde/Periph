///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.SPIConnection;
import it.uhde.periph.chips.accelerometer.Adxl362Minimal;

public class Minimal {
    static int passed = 0, failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("SPI_BUS", "0"));
        int dev = Integer.parseInt(System.getenv().getOrDefault("SPI_DEVICE", "0"));

        try (var connection = new SPIConnection(bus, dev, 0, 8_000_000)) {        // Create SPI connection, (bus, dev, mode=0, maxSpeedHz=8e6) → SPIConnection
            var chip = new Adxl362Minimal(connection);                            // Create ADXL362 driver, (connection) → ADXL362Minimal

            for (int i = 0; i < 5; i++) {
                float[] xyz = chip.read();                                         // Read 3-axis acceleration, () → float[3] g
                System.out.printf("x=%+.3f  y=%+.3f  z=%+.3f g%n", xyz[0], xyz[1], xyz[2]);
                Thread.sleep(100);
            }

            checkTrue("construct_minimal", chip != null);
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}