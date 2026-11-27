///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.6.0
//DEPS it.uhde:periph-java:1.6.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.Shtc3Full;

public class Shtc3Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x70").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Shtc3Full(connection);

            sensor.init();
            double[] v = sensor.read();
            checkTrue("read_temperature_range", v[0] > -45 && v[0] < 130);
            checkTrue("read_humidity_range", v[1] >= 0 && v[1] <= 100);
            double h = sensor.readHumidity();
            checkTrue("read_humidity_single", h >= 0 && h <= 100);
            double t = sensor.readTemperature();
            checkTrue("read_temperature_single", t > -45 && t < 130);
            int[] raw = sensor.readRaw();
            checkTrue("read_raw_range", raw[0] >= 0 && raw[0] <= 65535 && raw[1] >= 0 && raw[1] <= 65535);
            checkTrue("dew_point_below_temperature", sensor.dewPoint() <= t + 1);
            checkTrue("read_id_product_code", (sensor.readId() & 0x083F) == 0x0807);
            sensor.setLowPower(true);
            double[] lp = sensor.read();
            checkTrue("low_power_read", lp[1] >= 0 && lp[1] <= 100 && lp[0] > -45);
            sensor.setLowPower(false);
            sensor.setSleepBetween(false);
            checkTrue("read_without_sleep", sensor.read()[1] >= 0);
            checkTrue("read_without_sleep_again", sensor.read()[1] >= 0);
            sensor.sleep();
            sensor.wake();
            sensor.setSleepBetween(true);
            sensor.setClockStretching(true);
            checkTrue("read_with_clock_stretching", sensor.read()[1] >= 0);
            sensor.setClockStretching(false);
            sensor.softReset();
            checkTrue("read_after_reset", sensor.read()[1] >= 0);
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
