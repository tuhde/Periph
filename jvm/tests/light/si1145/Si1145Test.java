///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.light.Si1145Full;

public class Si1145Test {

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
            var sensor = new Si1145Full(connection);
            sensor.init();
            int part = sensor.readId().partId();
            checkTrue("part_id", part >= 0x45 && part <= 0x47);
            checkTrue("response_no_error", sensor.readResponse().error() == 0);
            var m = sensor.read();
            checkTrue("read_uv_index_range", m.uvIndex() >= 0 && m.uvIndex() < 20);
            checkTrue("read_visible_positive", m.visible() > 0);
            checkTrue("read_infrared_positive", m.infrared() > 0);
            int p = sensor.readProximity();
            checkTrue("read_proximity_single", p >= 0 && p <= 0xFFFF);
            checkTrue("read_uv_index_single", sensor.readUvIndex() < 20);
            checkTrue("read_visible_single", sensor.readVisible() > 0);
            checkTrue("read_infrared_single", sensor.readInfrared() > 0);
            checkTrue("read_raw", sensor.readRaw() != null);
            checkTrue("read_temperature_raw", sensor.readTemperatureRaw() > 0);
            checkTrue("read_vdd_raw", sensor.readVddRaw() > 0);
            sensor.setLedCurrent(1, 0);
            int dark = sensor.readProximity();
            sensor.setLedCurrent(1, 15);
            int lit = sensor.readProximity();
            checkTrue("led_current_effect", lit >= dark);
            sensor.setLedCurrent(1, 3);
            sensor.setPsPhotodiode(1, true);
            sensor.setGain(2, 2, 0);
            checkTrue("gain_read", sensor.readVisible() > 0);
            sensor.setGain(0, 0, 0);
            sensor.setHighRange(true, true, false);
            checkTrue("high_range_read", sensor.readVisible() >= 0);
            sensor.setHighRange(false, false, false);
            sensor.setAlignment(false, false, false, false, false);
            sensor.setUvCoefficients(Si1145Full.UCOEF_DEFAULT);
            checkTrue("read_calibration_len", sensor.readCalibration().length == 12);
            sensor.setChannels(true, true, true, true);
            sensor.pollInterrupt();
            sensor.startAutonomous(0.05);
            Thread.sleep(500);
            checkTrue("autonomous_irq", (sensor.pollInterrupt() & 0x05) != 0);
            sensor.pause();
            sensor.force();
            Thread.sleep(100);
            checkTrue("force_irq", sensor.pollInterrupt() != 0);
            sensor.enableInterrupt(Si1145Full.SOURCE_CMD);
            sensor.disableInterrupt(Si1145Full.SOURCE_CMD);
            sensor.reset();
            checkTrue("reset_no_error", sensor.readResponse().error() == 0);
            checkTrue("read_after_reset", sensor.read().visible() > 0);
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
