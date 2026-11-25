///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.power.AXP2101Minimal;
import it.uhde.periph.chips.power.AXP2101Full;

public class AXP2101Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x34").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var chip = new AXP2101Minimal(connection);
            checkTrue("construct_minimal", true);
            double v = chip.batteryVoltage();
            checkTrue("battery_voltage_plausible", v >= 0.0 && v <= 5.0);
            int pct = chip.batteryPercent();
            checkTrue("battery_percent_range", pct >= 0 && pct <= 100);
            int state = chip.chargeState();
            checkTrue("charge_state_range", state >= 0 && state <= 5);
            chip.vbusPresent();
            checkTrue("vbus_present_no_error", true);

            var full = new AXP2101Full(connection);
            checkTrue("construct_full", true);
            checkTrue("vbus_voltage_range", full.vbusVoltage() >= 0.0 && full.vbusVoltage() <= 15.0);
            checkTrue("system_voltage_range", full.systemVoltage() >= 0.0 && full.systemVoltage() <= 6.0);
            checkTrue("ts_voltage_range", full.tsVoltage() >= 0.0 && full.tsVoltage() <= 4.0);
            double t = full.dieTemperature();
            checkTrue("die_temperature_range", t >= -40.0 && t <= 150.0);
            checkTrue("read_all", full.readAll().batteryPercent() >= 0);
            String dir = full.status().batteryDirection();
            checkTrue("status_direction", dir.equals("standby") || dir.equals("charge")
                    || dir.equals("discharge") || dir.equals("reserved"));
            var rail = full.getRail("dcdc1");
            checkTrue("get_rail_dcdc1", rail.volts() >= 1.4 && rail.volts() <= 3.5);

            full.setInputVoltageLimit(4.36);
            checkTrue("set_input_voltage_limit", true);
            full.writeDataBuffer(0, 0x5A);
            checkTrue("data_buffer_roundtrip", full.readDataBuffer(0) == 0x5A);
            full.enableInterrupt(AXP2101Full.SOURCE_VBUS_INSERT);
            full.pollInterrupt();
            checkTrue("poll_interrupt", true);
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
