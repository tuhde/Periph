///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.Si7021Full;

public class Si7021Test {

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
            var sensor = new Si7021Full(connection);

            sensor.softReset();
            checkTrue("device type", sensor.deviceType() == 0x15);
            checkTrue("serial number", sensor.serialNumber() != 0);
            checkTrue("user register after reset", (sensor.readUserRegister() & 0xBF) == 0x3A);

            double[] v = sensor.read();
            checkTrue("temperature range", v[0] > -40 && v[0] < 125);
            checkTrue("humidity range", v[1] >= 0 && v[1] <= 100);
            checkTrue("readHumidity range", sensor.readHumidity() >= 0);
            checkTrue("readTemperature range", sensor.readTemperature() > -40);
            checkTrue("readTemperaturePrevious range", sensor.readTemperaturePrevious() > -40);
            checkTrue("raw humidity range", sensor.readRawHumidity() <= 65535);
            checkTrue("raw temperature range", sensor.readRawTemperature() <= 65535);

            int[][] pairs = {{12, 14}, {8, 12}, {10, 13}, {11, 11}};
            for (int[] p : pairs) {
                sensor.setResolution(p[0], p[1]);
                int[] r = sensor.getResolution();
                checkTrue("resolution " + p[0] + "/" + p[1], r[0] == p[0] && r[1] == p[1]);
                double[] w = sensor.read();
                checkTrue("read at " + p[0] + "/" + p[1], w[0] > -40 && w[0] < 125 && w[1] >= 0 && w[1] <= 100);
            }
            sensor.setResolution(12, 14);
            double ct = sensor.conversionTime();
            checkTrue("conversion time", ct > 0.005 && ct < 0.03);

            sensor.setHeater(true);
            checkTrue("heater on", sensor.isHeaterOn());
            sensor.setHeater(false);
            checkTrue("heater off", !sensor.isHeaterOn());
            checkTrue("vdd ok", !sensor.vddLow());
            checkTrue("firmware readable", sensor.firmwareRevision() >= 0);

            double dp = sensor.dewPoint();
            checkTrue("dew point plausible", dp > -60 && dp < 60);
            sensor.softReset();
            checkTrue("read after software reset", sensor.read()[1] >= 0);
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
