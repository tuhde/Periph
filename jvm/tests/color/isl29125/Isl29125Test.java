///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-java:1.5.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.color.Isl29125Minimal;
import it.uhde.periph.chips.color.Isl29125Full;

public class Isl29125Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    static boolean inRange(int v) { return v >= 0 && v <= 0xFFFF; }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x44"));

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Isl29125Minimal(connection);
            checkTrue(true, "construct_minimal");
            var c = sensor.read();
            checkTrue(inRange(c.red) && inRange(c.green) && inRange(c.blue), "read_range");

            var full = new Isl29125Full(connection);
            checkTrue(full.deviceId() == 0x7D, "device_id");
            full.setRange(375);
            full.setRange(10000);
            full.setResolution(12);
            Thread.sleep(400);
            var b12 = full.readRgb();
            checkTrue(inRange(b12.red) && inRange(b12.green) && inRange(b12.blue), "read_rgb_range_12");
            full.setResolution(16);
            full.setIrCompensation(true, 0x3F);
            full.setSync(false);
            Thread.sleep(400);
            var b = full.readRgb();
            checkTrue(inRange(b.red) && inRange(b.green) && inRange(b.blue), "read_rgb_range");
            checkTrue(inRange(full.readGreen()) && inRange(full.readRed()) && inRange(full.readBlue()), "read_single");
            full.setMode(Isl29125Full.MODE_GREEN);
            Thread.sleep(300);
            checkTrue(inRange(full.readGreen()), "green_mode_reads");
            full.setMode(Isl29125Full.MODE_RGB);
            full.setThresholds(100, 60000);
            var t = full.getThresholds();
            checkTrue(t[0] == 100 && t[1] == 60000, "thresholds_roundtrip");
            full.setPersistence(2);
            full.setInterruptChannel(Isl29125Full.CHANNEL_GREEN);
            full.enableInterrupt(Isl29125Full.SOURCE_THRESHOLD);
            full.disableInterrupt(Isl29125Full.SOURCE_THRESHOLD);
            full.status();
            checkTrue((full.pollInterrupt() & Isl29125Minimal.STATUS_THRESHOLD) == 0, "status_clean");
            full.setMode(Isl29125Full.MODE_POWER_DOWN);
            full.softReset();
            full.setMode(Isl29125Full.MODE_RGB);
            checkTrue(full.deviceId() == 0x7D, "soft_reset_id");
        }
        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
