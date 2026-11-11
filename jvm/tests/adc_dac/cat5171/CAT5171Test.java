///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.CAT5171Minimal;
import it.uhde.periph.chips.adc_dac.CAT5171Full;

public class CAT5171Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x2C"));

        try (var connection = new I2CConnection(bus, addr)) {
            var pot = new CAT5171Minimal(connection);
            checkTrue(true, "construct_minimal");

            pot.setWiper(100);
            checkTrue(pot.getWiper() == 100, "wiper_roundtrip");
            pot.setWiper(300);
            checkTrue(pot.getWiper() == 255, "wiper_clamps_255");
            pot.setResistance(50000.0);
            checkTrue(Math.abs(pot.getResistance() - 50000.0) < 500.0, "set_resistance");

            var full = new CAT5171Full(connection);
            full.setRatio(0.5);
            checkTrue(full.getWiper() == 128 && Math.abs(full.getRatio() - 0.5) < 0.01, "ratio_half");
            full.setResistanceWa(30000.0);
            checkTrue(Math.abs(full.getResistanceWa() - 30000.0) < 500.0, "set_resistance_wa");
            full.setWiper(100);
            checkTrue(full.increment(5) == 105 && full.getWiper() == 105, "increment");
            checkTrue(full.increment(-500) == 0, "increment_clamps");

            full.setWiper(77);
            full.shutdown();
            checkTrue(full.isShutdown(), "is_shutdown");
            checkTrue(full.getWiper() == 77, "shutdown_keeps_wcr");
            full.resume();
            checkTrue(!full.isShutdown() && full.getWiper() == 77, "resume");

            full.setWiper(10);
            full.midscale();
            checkTrue(full.getWiper() == 128, "midscale");

            full.setWiper(1);
            full.reset();
            checkTrue(full.getWiper() == 128, "reset_midscale");
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
