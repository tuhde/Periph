///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.4
//DEPS it.uhde:periph-java:1.5.4

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.pressure.MPL115A2Full;

public class MPL115A2Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        // MPL115A2 has a fixed I²C address of 0x60; I2C_ADDR is intentionally ignored.
        int addr = 0x60;

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new MPL115A2Full(connection);

            double p = sensor.readPressure();
            checkTrue("readPressure() in range [50000, 115000] Pa", p >= 50000 && p <= 115000);
            double t = sensor.readTemperature();
            checkTrue("readTemperature() in range [-40, 105] °C", t >= -40 && t <= 105);
            int[] raw = sensor.readRaw();
            checkTrue("readRaw() counts in 0..1023", raw[0] >= 0 && raw[0] <= 1023 && raw[1] >= 0 && raw[1] <= 1023);
            double c = sensor.compensate(raw[0], raw[1]);
            checkTrue("compensate() in range", c >= 50000 && c <= 115000);
            double[] both = sensor.read();
            checkTrue("read() in range", both[0] >= 50000 && both[0] <= 115000 && both[1] >= -40 && both[1] <= 105);
            double hpa = sensor.pressureHpa();
            checkTrue("pressureHpa() in range", hpa >= 500 && hpa <= 1150);
            double alt = sensor.altitude();
            checkTrue("altitude() in range", alt >= -1000 && alt <= 6000);
            sensor.reloadCoefficients();
            checkTrue("coefficients() a0 != 0", sensor.coefficients()[0] != 0.0);
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
