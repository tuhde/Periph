///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.1
//DEPS it.uhde:periph-java:1.2.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.temperature.MCP9808Minimal;
import it.uhde.periph.chips.temperature.MCP9808Full;

public class MCP9808Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x18"));

        try (var connection = new I2CConnection(bus, addr)) {
            var minimal = new MCP9808Minimal(connection);
            checkTrue(true, "construct_minimal");
            double t = minimal.readTemperature();
            checkTrue(t >= -40 && t <= 125, "temperature_plausible");

            var s = new MCP9808Full(connection);
            s.setResolution(0.5);
            checkTrue(s.getResolution() == 0.5, "resolution_0_5");
            s.setResolution(0.0625);
            checkTrue(s.getResolution() == 0.0625, "resolution_0_0625");

            s.setUpperLimit(80.0);
            checkTrue(s.getUpperLimit() == 80.0, "upper_limit_roundtrip");
            s.setLowerLimit(-10.25);
            checkTrue(s.getLowerLimit() == -10.25, "lower_limit_roundtrip");
            s.setCriticalLimit(100.0);
            checkTrue(s.getCriticalLimit() == 100.0, "critical_limit_roundtrip");

            s.setHysteresis(1.5);
            checkTrue(s.getHysteresis() == 1.5, "hysteresis_roundtrip");
            s.setHysteresis(0.0);

            s.shutdown();
            checkTrue(s.isShutdown(), "shutdown");
            s.wake();
            checkTrue(!s.isShutdown(), "wake");

            // Lower limit above ambient forces TA < TLOWER; the status bit is live
            // regardless of whether the Alert output is enabled.
            s.setLowerLimit(t + 20.0);
            Thread.sleep(300);
            checkTrue((s.pollInterrupt() & MCP9808Full.SOURCE_LOWER) != 0, "poll_interrupt_lower");
            s.setLowerLimit(-10.25);
            Thread.sleep(300);
            checkTrue((s.pollInterrupt() & MCP9808Full.SOURCE_LOWER) == 0, "poll_interrupt_clear");

            s.configureAlert();
            s.enableAlert();
            checkTrue(!s.isAlertAsserted(), "alert_not_asserted_in_window");
            s.disableAlert();
            s.clearInterrupt();

            // The lock bits are one-way until power-on reset and are never set here.
            checkTrue(!s.isCriticalLimitLocked(), "not_critical_locked");
            checkTrue(!s.isWindowLimitsLocked(), "not_window_locked");
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
