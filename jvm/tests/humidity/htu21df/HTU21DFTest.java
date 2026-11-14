///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.HTU21DFFull;

public class HTU21DFTest {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x40").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new HTU21DFFull(connection);

            sensor.init();
            checkTrue("user register after reset", (sensor.readUserRegister() & 0x83) == 0x02);

            double[] v = sensor.read();
            checkTrue("temperature range", v[0] > -40 && v[0] < 125);
            checkTrue("humidity range", v[1] >= -6 && v[1] <= 119);
            checkTrue("readHumidity range", sensor.readHumidity() >= -6);
            checkTrue("readTemperature range", sensor.readTemperature() > -40);
            checkTrue("readHumidityCompensated range", sensor.readHumidityCompensated() >= -6);
            int[] raw = sensor.readRaw();
            checkTrue("raw ranges", raw[0] <= 65535 && raw[1] <= 65535);

            for (int res = 0; res < 4; res++) {
                sensor.setResolution(res);
                checkTrue("resolution " + res, sensor.getResolution() == res);
                double[] w = sensor.read();
                checkTrue("read at res " + res, w[0] > -40 && w[0] < 125 && w[1] >= -6 && w[1] <= 119);
            }
            sensor.setResolution(0);

            sensor.setHeater(true);
            checkTrue("heater on", sensor.heater());
            sensor.setHeater(false);
            checkTrue("heater off", !sensor.heater());
            checkTrue("battery ok", !sensor.batteryLow());

            double dp = sensor.dewPoint();
            checkTrue("dew point plausible", dp > -60 && dp < 60);
            sensor.softReset();
            checkTrue("read after soft reset", sensor.read()[1] >= -6);
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
