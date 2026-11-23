///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-java:1.5.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.HS30xxFull;

public class HS30xxTest {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x44").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new HS30xxFull(connection);

            double[] v = sensor.read();
            checkTrue("temperature range", v[0] >= -40 && v[0] <= 125);
            checkTrue("humidity range", v[1] >= 0 && v[1] <= 100);
            checkTrue("readTemperature range", sensor.readTemperature() >= -40);
            checkTrue("readHumidity range", sensor.readHumidity() >= 0);
            int[] raw = sensor.readRaw();
            checkTrue("raw ranges", raw[0] <= 16383 && raw[1] <= 16383);
            checkTrue("raw status valid", raw[2] == 0);

            sensor.setMeasurementWait(60);
            checkTrue("read with 60 ms wait", sensor.readHumidity() >= 0);
            sensor.setMeasurementWait(40);

            double dp = sensor.dewPoint();
            checkTrue("dew point plausible", dp > -60 && dp < 60);

            // NVM access (resolution, ID) needs a power-cycle helper and is not exercised here.
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
