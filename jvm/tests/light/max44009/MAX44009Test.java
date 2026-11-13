///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.light.MAX44009Minimal;
import it.uhde.periph.chips.light.MAX44009Full;

public class MAX44009Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x4A"));

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new MAX44009Minimal(connection);
            checkTrue(true, "construct_minimal");
            double lx = sensor.lux();
            checkTrue(lx >= 0.0 && lx <= 188006.4, "lux_in_range");

            var full = new MAX44009Full(connection);
            var raw = full.readRaw();
            checkTrue(raw.exponent <= 15 && raw.overrange == (raw.exponent == 15), "read_raw_ranges");
            full.setContinuous(true);
            checkTrue(full.getConfig().continuous, "continuous_on");
            full.setContinuous(false);
            checkTrue(!full.getConfig().continuous, "continuous_off");
            full.setManual(100.0, false);
            var cfg = full.getConfig();
            checkTrue(cfg.manual && cfg.integrationMs == 100.0 && !cfg.divideBy8, "manual_100ms");
            full.setManual(25.0, true);
            cfg = full.getConfig();
            checkTrue(cfg.integrationMs == 25.0 && cfg.divideBy8, "manual_25ms_divide");
            full.setAutomatic();
            checkTrue(!full.getConfig().manual, "automatic");
            full.setThresholds(50.0, 500.0);
            double[] w = full.getThresholds();
            checkTrue(Math.abs(w[0] - 50.0) / 50.0 < 0.2 && Math.abs(w[1] - 500.0) / 500.0 < 0.2, "thresholds_roundtrip");
            full.setThresholdDelay(2.0);
            checkTrue(Math.abs(full.getThresholdDelay() - 2.0) < 0.05, "threshold_delay");
            full.enableInterrupt();
            full.disableInterrupt();
            checkTrue(full.pollInterrupt() == 0, "poll_interrupt_cleared");
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
