///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.AD5243Minimal;
import it.uhde.periph.chips.adc_dac.AD5243Full;

public class AD5243Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x2F"));

        try (var connection = new I2CConnection(bus, addr)) {
            var pot = new AD5243Minimal(connection);
            checkTrue(true, "construct_minimal");

            pot.setWiper(0, 100);
            checkTrue(pot.getWiper(0) == 100, "wiper0_roundtrip");
            pot.setWiper(1, 200);
            checkTrue(pot.getWiper(1) == 200, "wiper1_roundtrip");
            checkTrue(pot.getWiper(0) == 100, "wiper0_independent");
            pot.setWiper(0, 300);
            checkTrue(pot.getWiper(0) == 255, "wiper_clamps_255");
            pot.setResistance(0, 5000.0);
            checkTrue(Math.abs(pot.getResistance(0) - 5000.0) < 50.0, "set_resistance");

            var full = new AD5243Full(connection);
            full.setBoth(10, 250);
            checkTrue(full.getWiper(0) == 10 && full.getWiper(1) == 250, "set_both");
            full.setRatio(0, 0.5);
            checkTrue(full.getWiper(0) == 128 && Math.abs(full.getRatio(0) - 0.5) < 0.01, "ratio_half");
            full.setResistanceWa(1, 3000.0);
            checkTrue(Math.abs(full.getResistanceWa(1) - 3000.0) < 50.0, "set_resistance_wa");
            full.setWiper(0, 100);
            checkTrue(full.increment(0, 5) == 105 && full.getWiper(0) == 105, "increment");
            checkTrue(full.increment(0, -500) == 0, "increment_clamps");

            full.setWiper(1, 77);
            full.shutdown(1);
            checkTrue(full.isShutdown(1), "is_shutdown");
            checkTrue(full.getWiper(1) == 77, "shutdown_keeps_rdac");
            full.resume(1);
            checkTrue(!full.isShutdown(1) && full.getWiper(1) == 77, "resume");

            full.setBoth(1, 2);
            full.reset();
            checkTrue(full.getWiper(0) == 128 && full.getWiper(1) == 128, "reset_midscale");
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
