///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.light.TSL2561Minimal;
import it.uhde.periph.chips.light.TSL2561Full;

public class TSL2561Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x39"));

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new TSL2561Minimal(connection);
            checkTrue(true, "construct_minimal");
            double lx = sensor.readLux();
            checkTrue(lx >= 0.0 && lx <= 40000.0, "read_lux_in_range");

            var full = new TSL2561Full(connection);
            checkTrue(full.readId() >> 4 == 1, "read_id_partno");
            checkTrue(full.isPowered(), "is_powered");
            int[] raw = full.readRaw();
            checkTrue(raw[0] >= 0 && raw[0] <= 65535 && raw[1] >= 0 && raw[1] <= 65535, "read_raw_ranges");
            full.setGain(16);
            full.setIntegrationTime(101);
            checkTrue(full.getGain() == 16 && full.getIntegrationTime() == 101, "gain_integration_roundtrip");
            full.setGain(1);
            full.setIntegrationTime(402);
            checkTrue(full.autoRange() >= 0.0, "auto_range_lux");
            full.setThresholds(100, 30000);
            int[] th = full.getThresholds();
            checkTrue(th[0] == 100 && th[1] == 30000, "thresholds_roundtrip");
            full.configureInterrupt(0, 0);
            full.clearInterrupt();
            full.pollInterrupt();
            checkTrue(true, "interrupt_register_access");
            full.powerDown();
            checkTrue(!full.isPowered(), "power_down");
            full.powerUp();
            checkTrue(full.isPowered(), "power_up");
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
