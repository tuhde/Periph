///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.SHT31Full;

public class SHT31Test {

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
            var sensor = new SHT31Full(connection);

            double[] v = sensor.read();
            checkTrue("temperature range", v[0] > -40 && v[0] < 125);
            checkTrue("humidity range", v[1] >= 0 && v[1] <= 100);
            checkTrue("readTemperature range", sensor.readTemperature() > -40);
            checkTrue("readHumidity range", sensor.readHumidity() >= 0);
            int[] raw = sensor.readRaw();
            checkTrue("readRaw range", raw[0] >= 0 && raw[0] <= 65535 && raw[1] >= 0 && raw[1] <= 65535);

            for (String level : new String[]{"low", "medium", "high"}) {
                sensor.setRepeatability(level);
                double[] w = sensor.read();
                checkTrue("repeatability " + level, w[0] > -40 && w[0] < 125 && w[1] >= 0 && w[1] <= 100);
            }

            checkTrue("status readable", sensor.readStatus() >= 0);
            sensor.clearStatus();
            checkTrue("status bit 4 cleared", (sensor.readStatus() & 0x0010) == 0);

            sensor.startPeriodic(2.0, "high", false);
            Thread.sleep(1200);
            double[] res = sensor.fetch();
            checkTrue("periodic fetch", res != null && res[0] > -40 && res[0] < 125);
            sensor.stopPeriodic();

            sensor.setHeater(true);
            checkTrue("heater bit set", (sensor.readStatus() & 0x2000) != 0);
            sensor.setHeater(false);
            checkTrue("heater bit cleared", (sensor.readStatus() & 0x2000) == 0);

            double dp = sensor.dewPoint();
            checkTrue("dew point plausible", dp > -60 && dp < 60);
            sensor.softReset();
            checkTrue("read after soft reset", sensor.read()[1] >= 0);
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
