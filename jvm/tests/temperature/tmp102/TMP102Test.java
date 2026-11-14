///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.temperature.TMP102Minimal;
import it.uhde.periph.chips.temperature.TMP102Full;

public class TMP102Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x48"));

        try (var connection = new I2CConnection(bus, addr)) {
            var minimal = new TMP102Minimal(connection);
            var s = new TMP102Full(connection);
            Thread.sleep(50);
            double t = minimal.readTemperature();
            checkTrue(t >= -40 && t <= 125, "temperature_plausible");
            checkTrue(Math.abs(s.readRaw() * 0.0625 - t) < 1.0, "raw_matches_temperature");
            checkTrue((s.readConfig() & 0x6000) == 0x6000, "default_config");

            s.setConversionRate(1);
            checkTrue(((s.readConfig() >> 6) & 3) == 1, "rate_roundtrip");
            s.setConversionRate(4);

            s.setTHigh(40.0);
            checkTrue(s.getTHigh() == 40.0, "t_high_roundtrip");
            s.setTLow(-10.25);
            checkTrue(s.getTLow() == -10.25, "t_low_roundtrip");
            s.setTHigh(80.0);
            s.setTLow(75.0);

            s.setFaultQueue(4);
            checkTrue(((s.readConfig() >> 11) & 3) == 2, "fault_queue");
            s.setFaultQueue(1);
            s.setPolarity(true);
            checkTrue((s.readConfig() & 0x0400) != 0, "polarity");
            s.setPolarity(false);
            s.setThermostatMode(true);
            checkTrue((s.readConfig() & 0x0200) != 0, "thermostat_mode");
            s.setThermostatMode(false);

            s.setExtendedMode(true);
            Thread.sleep(300);
            checkTrue(Math.abs(s.readTemperature() - t) < 3.0, "extended_mode_reading");
            s.setExtendedMode(false);

            s.shutdown();
            checkTrue((s.readConfig() & 0x0100) != 0, "shutdown");
            long start = System.currentTimeMillis();
            double t1 = s.oneShot();
            checkTrue(t1 >= -40 && t1 <= 125, "one_shot_plausible");
            checkTrue(System.currentTimeMillis() - start < 100, "one_shot_fast");
            checkTrue((s.readConfig() & 0x0100) != 0, "one_shot_stays_shutdown");
            s.wake();
            checkTrue((s.readConfig() & 0x0100) == 0, "wake");

            // T_HIGH below ambient forces an alert after the fault queue elapses.
            s.setThermostatMode(false);
            s.setTLow(t - 21);
            s.setTHigh(t - 20);
            Thread.sleep(500);
            checkTrue(s.alertState(), "alert_state_active");
            checkTrue(s.pollInterrupt(), "poll_interrupt_active");
            s.setTHigh(80.0);
            s.setTLow(75.0);
            Thread.sleep(500);
            checkTrue(!s.alertState(), "alert_state_clear");
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
