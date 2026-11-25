///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-java:1.5.4

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.Hts221Full;

public class Hts221Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x5F").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new Hts221Full(connection);

            checkTrue("who_am_i", sensor.whoAmI() == 0xBC);
            double[] cal = sensor.readCalibration();
            checkTrue("calibration_plausible", cal[1] > cal[0] && cal[3] > cal[2]);
            double[] v = sensor.read();
            checkTrue("read_temperature_range", v[0] > -40 && v[0] < 85);
            checkTrue("read_humidity_range", v[1] >= 0 && v[1] <= 100);
            double h = sensor.readHumidity();
            checkTrue("read_humidity_single", h >= 0 && h <= 100);
            double t = sensor.readTemperature();
            checkTrue("read_temperature_single", t > -40 && t < 85);
            checkTrue("dew_point_below_temperature", sensor.dewPoint() <= t + 1);
            sensor.setAveraging(32, 64);
            int[] avg = sensor.getAveraging();
            checkTrue("averaging_roundtrip", avg[0] == 32 && avg[1] == 64);
            sensor.setAveraging(16, 32);
            sensor.setOutputDataRate(1);
            checkTrue("odr_roundtrip", sensor.getOutputDataRate() == 1.0);
            Thread.sleep(1500);
            double[] c = sensor.readContinuous();
            checkTrue("continuous_read", c[1] >= 0 && c[1] <= 100);
            sensor.setOutputDataRate(0);
            sensor.setHeater(true);
            checkTrue("heater_on", sensor.isHeaterOn());
            sensor.setHeater(false);
            checkTrue("heater_off", !sensor.isHeaterOn());
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
