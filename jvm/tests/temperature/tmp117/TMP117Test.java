///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-java:1.2.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.temperature.TMP117Minimal;
import it.uhde.periph.chips.temperature.TMP117Full;

public class TMP117Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x48"));

        try (var connection = new I2CConnection(bus, addr)) {
            var minimal = new TMP117Minimal(connection);
            checkTrue(true, "construct_minimal");

            var s = new TMP117Full(connection);
            s.configure(TMP117Full.Mode.CONTINUOUS, 8, 0.125);
            Thread.sleep(300);
            double t = minimal.readTemperature();
            checkTrue(t >= -40 && t <= 125, "temperature_plausible");

            s.configure(TMP117Full.Mode.CONTINUOUS, 32, 4.0);
            checkTrue(s.getConfig().equals(new TMP117Full.Config(TMP117Full.Mode.CONTINUOUS, 32, 4.0)), "config_roundtrip");
            s.configure(TMP117Full.Mode.SHUTDOWN, 0, 0.0155);
            checkTrue(s.isShutdown(), "shutdown");
            s.triggerOneShot();
            Thread.sleep(50);
            checkTrue(s.isDataReady(), "one_shot_data_ready");
            checkTrue(s.isShutdown(), "one_shot_returns_to_shutdown");
            s.configure();
            checkTrue(!s.isShutdown(), "continuous");

            s.setHighLimit(80.0);
            checkTrue(s.getHighLimit() == 80.0, "high_limit_roundtrip");
            s.setLowLimit(-10.25);
            checkTrue(s.getLowLimit() == -10.25, "low_limit_roundtrip");
            s.setTemperatureOffset(0.5);
            checkTrue(s.getTemperatureOffset() == 0.5, "offset_roundtrip");
            s.setTemperatureOffset(0.0);

            // The EEPROM is never unlocked here, so no power-on default changes.
            checkTrue(!s.isEepromBusy(), "eeprom_not_busy");
            s.writeEepromScratch(2, 0xA55A);
            checkTrue(s.readEepromScratch(2) == 0xA55A, "eeprom2_volatile_roundtrip");

            // High limit below ambient forces HIGH_Alert on the next conversion; in
            // Alert mode the flag latches until CONFIGURATION is read.
            s.configureAlert();
            s.configure(TMP117Full.Mode.CONTINUOUS, 0, 0.0155);
            s.setHighLimit(t - 20.0);
            Thread.sleep(100);
            checkTrue((s.pollInterrupt() & TMP117Full.SOURCE_HIGH) != 0, "poll_interrupt_high");
            s.setHighLimit(80.0);
            Thread.sleep(100);
            s.pollInterrupt();
            checkTrue((s.pollInterrupt() & TMP117Full.SOURCE_HIGH) == 0, "poll_interrupt_clear");

            // Soft reset reloads CONFIGURATION, the limits and the offset from EEPROM.
            s.reset();
            checkTrue(s.getConfig().mode() == TMP117Full.Mode.CONTINUOUS, "reset_restores_config");
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
