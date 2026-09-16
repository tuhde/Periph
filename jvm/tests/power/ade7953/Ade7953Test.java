///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.Ade7953Full;

public class Ade7953Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x38").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var chip = new Ade7953Full(connection, 251.0, 30.0);

            checkTrue("voltage non-negative", chip.voltage() >= 0.0);
            checkTrue("current non-negative", chip.current() >= 0.0);
            checkTrue("activePower finite",   chip.activePower() > -1.0e6);
            checkTrue("activeEnergy finite",  chip.activeEnergy() > -1000.0);
            checkTrue("linePeriod positive",  chip.linePeriod() > 0.0);

            chip.reset();
            checkTrue("voltage after reset", chip.voltage() >= 0.0);

            System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        }
        System.exit(failed == 0 ? 0 : 1);
    }
}