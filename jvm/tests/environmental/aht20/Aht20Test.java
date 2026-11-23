///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.1
//DEPS it.uhde:periph-java:1.5.1

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.environmental.Aht20Full;

public class Aht20Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x38").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {

            var aht = new Aht20Full(connection);

            checkTrue("isCalibrated", aht.isCalibrated());
            checkTrue("not busy at idle", !aht.isBusy());

            double[] r = aht.read();
            checkTrue("temperature range", r[0] >= -40.0 && r[0] <= 120.0);
            checkTrue("humidity range", r[1] >= 0.0 && r[1] <= 100.0);

            double tr = aht.readTemperature();
            checkTrue("readTemperature range", tr >= -40.0 && tr <= 120.0);

            double hr = aht.readHumidity();
            checkTrue("readHumidity range", hr >= 0.0 && hr <= 100.0);

            int[] raw = aht.readRaw();
            checkTrue("readRaw range", raw[0] >= 0 && raw[0] < 1048576 && raw[1] >= 0 && raw[1] < 1048576);
            checkTrue("readStatusByte calibrated", (aht.readStatusByte() & 0x08) != 0);
            double dew = aht.dewPoint();
            checkTrue("dewPoint range", dew > -60.0 && dew < 120.0);

            aht.softReset();
            Thread.sleep(50);
            checkTrue("calibrated after reset", aht.isCalibrated());

            double[] r2 = aht.read();
            checkTrue("read after reset: temperature range", r2[0] >= -40.0 && r2[0] <= 120.0);
            checkTrue("read after reset: humidity range", r2[1] >= 0.0 && r2[1] <= 100.0);

        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
