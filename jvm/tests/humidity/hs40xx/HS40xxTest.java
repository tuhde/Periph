///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-java:1.5.4

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.HS40xxFull;

public class HS40xxTest {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x54").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new HS40xxFull(connection);

            double[] v = sensor.read();
            checkTrue("temperature range", v[0] >= -40 && v[0] <= 125);
            checkTrue("humidity range", v[1] >= 0 && v[1] <= 100);
            checkTrue("readTemperature range", sensor.readTemperature() >= -40);
            checkTrue("readHumidity range", sensor.readHumidity() >= 0);
            int[] raw = sensor.readRaw();
            checkTrue("raw ranges", raw[0] <= 16383 && raw[1] <= 16383);
            checkTrue("id nonzero", sensor.readId() != 0);

            sensor.setResolution(12, 10);
            int[] res = sensor.getResolution();
            checkTrue("resolution readback", res[0] == 12 && res[1] == 10);
            sensor.setResolution(14, 14);

            sensor.startPeriodic(0.5);
            Thread.sleep(1000);
            double[] p = sensor.readPeriodic();
            checkTrue("periodic humidity", p[1] >= 0 && p[1] <= 100);
            sensor.stopPeriodic();
            sensor.softReset();

            double dp = sensor.dewPoint();
            checkTrue("dew point plausible", dp > -60 && dp < 60);
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
