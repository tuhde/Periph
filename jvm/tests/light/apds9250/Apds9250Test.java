///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-java:1.5.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.light.Apds9250Minimal;
import it.uhde.periph.chips.light.Apds9250Full;

public class Apds9250Test {
    static int passed = 0, failed = 0;

    static void checkTrue(boolean cond, String label) {
        if (cond) { System.out.println("PASS " + label); passed++; }
        else      { System.out.println("FAIL " + label); failed++; }
    }

    static boolean inRange(int v) { return v >= 0 && v <= 0xFFFFF; }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x52"));

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Apds9250Minimal(connection);
            checkTrue(true, "construct_minimal");
            var c = sensor.read();
            checkTrue(inRange(c.red) && inRange(c.green) && inRange(c.blue) && inRange(c.ir), "read_range");

            var full = new Apds9250Full(connection);
            checkTrue(full.partId()[0] == 0xB, "part_id");
            checkTrue(full.getResolution() == 18 && full.getMeasurementRate() == 100 && full.getGain() == 3, "defaults");
            full.setGain(18);
            checkTrue(full.getGain() == 18, "gain_18");
            full.setGain(3);
            full.setResolution(16);
            checkTrue(full.getResolution() == 16 && Math.abs(full.integrationTime() - 0.025) < 1e-9, "resolution_16");
            full.setResolution(18);
            full.setMeasurementRate(200);
            checkTrue(full.getMeasurementRate() == 200, "rate_200");
            full.setMeasurementRate(100);
            var b = full.readChannels();
            checkTrue(inRange(b.green), "read_channels_range");
            checkTrue(inRange(full.readGreen()), "read_single_green");
            full.setMode(false);
            checkTrue(inRange(full.readIr()), "als_mode_reads");
            full.setMode(true);
            full.setThresholds(100, 200000);
            var t = full.getThresholds();
            checkTrue(t[0] == 100 && t[1] == 200000, "thresholds_roundtrip");
            full.setPersistence(3);
            full.setVariance(64);
            full.setInterruptChannel(Apds9250Full.CHANNEL_GREEN);
            full.enableInterrupt(Apds9250Full.SOURCE_VARIATION);
            full.disableInterrupt();
            full.status();
            checkTrue((full.pollInterrupt() & Apds9250Minimal.STATUS_INTERRUPT) == 0, "status_clean");
            full.disable();
            full.enable();
            full.softReset();
            checkTrue(full.getGain() == 3 && full.getResolution() == 18, "soft_reset_defaults");
        }
        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
