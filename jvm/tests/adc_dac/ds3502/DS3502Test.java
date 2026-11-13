///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.adc_dac.DS3502Minimal;
import it.uhde.periph.chips.adc_dac.DS3502Full;

public class DS3502Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x28"));

        try (var connection = new I2CConnection(bus, addr)) {
            var pot = new DS3502Minimal(connection, 15.0, 0.0);
            checkTrue(true, "construct_minimal");

            pot.setWiper(100);
            checkTrue(pot.getWiper() == 100, "wiper_roundtrip");
            pot.setWiper(300);
            checkTrue(pot.getWiper() == 127, "wiper_clamps_127");
            pot.setVoltage(6.0);
            checkTrue(Math.abs(pot.getVoltage() - 6.0) < 0.1, "set_voltage");

            var full = new DS3502Full(connection, 15.0, 0.0);
            checkTrue(!full.getMode(), "init_mode_sram");
            full.setRatio(0.5);
            checkTrue(full.getWiper() == 64 && Math.abs(full.getRatio() - 0.5) < 0.01, "ratio_half");
            full.setWiper(100);
            checkTrue(Math.abs(full.getResistance('L') + full.getResistance('H') - 10000.0) < 1.0, "resistance_nominal");
            checkTrue(full.increment(5) == 105 && full.getWiper() == 105, "increment");
            checkTrue(full.increment(-500) == 0, "increment_clamps");
            full.setMode(true);
            checkTrue(full.getMode(), "set_mode_eeprom");
            full.setMode(false);
            checkTrue(!full.getMode(), "set_mode_sram");
            full.waitReady();
            checkTrue(true, "wait_ready");
            // storeWiper is not exercised: it consumes one of the EEPROM's limited write cycles.
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
