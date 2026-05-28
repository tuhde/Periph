///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-transport:1.0-SNAPSHOT
//DEPS it.uhde:periph-java:1.0-SNAPSHOT

import it.uhde.periph.transport.I2CTransport;
import it.uhde.periph.chips.environmental.Aht21Full;

public class Aht21Test {

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

        try (var transport = new I2CTransport(bus, addr)) {

            var aht = new Aht21Full(transport);

            checkTrue("isCalibrated", aht.isCalibrated());
            checkTrue("not busy at idle", !aht.isBusy());

            double[] r = aht.read();
            checkTrue("temperature range", r[0] >= -40.0 && r[0] <= 120.0);
            checkTrue("humidity range", r[1] >= 0.0 && r[1] <= 100.0);

            double tr = aht.readTemperature();
            checkTrue("readTemperature range", tr >= -40.0 && tr <= 120.0);

            double hr = aht.readHumidity();
            checkTrue("readHumidity range", hr >= 0.0 && hr <= 100.0);

            double[] rc = aht.readWithCrc();
            checkTrue("crc_ok", rc[2] > 0.5);
            checkTrue("crc temperature range", rc[0] >= -40.0 && rc[0] <= 120.0);
            checkTrue("crc humidity range", rc[1] >= 0.0 && rc[1] <= 100.0);

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
