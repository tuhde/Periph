///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.light.Si1132Full;

public class Si1132Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x60").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Si1132Full(connection);

            double[] m = sensor.read();
            checkTrue("read_uv_index_range", m[0] >= 0 && m[0] < 20);
            checkTrue("read_counts_nonneg", m[1] >= 0 && m[2] >= 0);
            checkTrue("read_uv_index_single", sensor.readUvIndex() < 20);
            checkTrue("read_visible_single", sensor.readVisible() >= 0);
            checkTrue("read_infrared_single", sensor.readInfrared() >= 0);
            checkTrue("read_raw_length", sensor.readRaw().length == 3);
            checkTrue("temperature_raw", sensor.readTemperatureRaw() >= 0);
            checkTrue("vdd_raw", sensor.readVddRaw() >= 0);
            sensor.setGain(2, 2);
            checkTrue("gain_read", sensor.readVisible() >= 0);
            sensor.setGain(0, 0);
            sensor.setHighRange(true, true);
            checkTrue("high_range_read", sensor.readVisible() >= 0);
            sensor.setHighRange(false, false);
            sensor.setChannels(true, false, false, false);
            checkTrue("uv_only_read", sensor.read()[0] >= 0);
            sensor.setChannels(true, true, true, false);
            boolean rejected = false;
            try { sensor.setChannels(false, false, false, false); } catch (IllegalArgumentException e) { rejected = true; }
            checkTrue("channels_invalid", rejected);
            checkTrue("calibration_length", sensor.readCalibration().length == 12);
            sensor.force();
            Thread.sleep(50);
            checkTrue("measurement_ready", sensor.measurementReady());
            checkTrue("response_no_error", sensor.readResponse()[1] == 0);
            sensor.pollInterrupt();
            sensor.startAutonomous(0.1);
            Thread.sleep(500);
            checkTrue("autonomous_ready", sensor.measurementReady());
            sensor.pause();
            sensor.enableInterrupt();
            sensor.disableInterrupt();
            sensor.reset();
            checkTrue("read_after_reset", sensor.read()[1] >= 0);
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
