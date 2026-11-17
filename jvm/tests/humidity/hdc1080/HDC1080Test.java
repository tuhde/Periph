///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.0
//DEPS it.uhde:periph-java:1.5.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.humidity.HDC1080Full;

public class HDC1080Test {

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
            var sensor = new HDC1080Full(connection);

            sensor.init();
            checkTrue("config after init", (sensor.readConfig() & 0xFF00) == 0x1000);
            
            double[] v = sensor.read();
            checkTrue("temperature range", v[0] > -40 && v[0] < 125);
            checkTrue("humidity range", v[1] >= 0 && v[1] <= 100);
            checkTrue("readHumidity range", sensor.readHumidity() >= 0);
            checkTrue("readTemperature range", sensor.readTemperature() > -40);
            int[] raw = sensor.readRaw();
            checkTrue("raw ranges", raw[0] <= 65535 && raw[1] <= 65535);
            
            int[][] res = {{14, 14}, {11, 11}, {11, 8}, {14, 8}};
            for (int[] r : res) {
                sensor.setResolution(r[0], r[1]);
                double[] w = sensor.read();
                checkTrue("read at " + r[0] + "/" + r[1] + " bit", w[0] > -40 && w[0] < 125 && w[1] >= 0 && w[1] <= 100);
            }
            sensor.setResolution(14, 14);
            
            sensor.setHeater(true);
            checkTrue("heater on", sensor.isHeaterOn());
            sensor.setHeater(false);
            checkTrue("heater off", !sensor.isHeaterOn());
            checkTrue("battery ok", !sensor.batteryLow());
            checkTrue("serial id nonzero", sensor.serialId() != 0);
            checkTrue("manufacturer id", sensor.manufacturerId() == 0x5449);
            checkTrue("device id", sensor.deviceId() == 0x1050);
            checkTrue("conversion time", sensor.conversionTime() > 0.005 && sensor.conversionTime() < 0.02);
            sensor.softwareReset();
            checkTrue("config after reset", (sensor.readConfig() & 0xFF00) == 0x1000);

        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
