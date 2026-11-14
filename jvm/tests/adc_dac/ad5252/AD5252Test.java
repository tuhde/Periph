///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.AD5252Minimal;
import it.uhde.periph.chips.adc_dac.AD5252Full;

public class AD5252Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x2C"));

        try (var connection = new I2CConnection(bus, addr)) {
            var pot = new AD5252Minimal(connection);
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

            var full = new AD5252Full(connection);
            full.setBoth(10, 250);
            checkTrue(full.getWiper(0) == 10 && full.getWiper(1) == 250, "set_both");
            full.setRatio(0, 0.5);
            checkTrue(full.getWiper(0) == 128 && Math.abs(full.getRatio(0) - 0.5) < 0.01, "ratio_half");
            full.setResistanceWa(1, 3000.0);
            checkTrue(Math.abs(full.getResistanceWa(1) - 3000.0) < 50.0, "set_resistance_wa");
            full.setWiper(0, 100);
            full.increment(0);
            checkTrue(full.getWiper(0) == 101, "increment");
            full.decrement(0);
            checkTrue(full.getWiper(0) == 100, "decrement");
            full.doubleCode(0);
            checkTrue(full.getWiper(0) == 200, "double");
            full.halve(0);
            checkTrue(full.getWiper(0) == 100, "halve");
            full.setBoth(10, 20);
            full.increment();
            checkTrue(full.getWiper(0) == 11 && full.getWiper(1) == 21, "increment_both");

            full.setBoth(40, 60);
            full.store();
            full.setBoth(1, 2);
            full.restore();
            checkTrue(full.getWiper(0) == 40 && full.getWiper(1) == 60, "store_restore");
            full.setBoth(1, 2);
            full.reset();
            checkTrue(full.getWiper(0) == 40 && full.getWiper(1) == 60, "reset_restores_eemem");

            full.writeEemem(15, 0xA5);
            checkTrue(full.readEemem(15) == 0xA5, "eemem_roundtrip");
            double tol = full.readTolerance(0);
            checkTrue(tol > -50.0 && tol < 50.0, "tolerance_range");
            checkTrue(Math.abs(full.rabActual(0) - 10000.0 * (1.0 + tol / 100.0)) < 0.5, "rab_actual");
            full.nop();
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
