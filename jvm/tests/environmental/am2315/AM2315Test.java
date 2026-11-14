///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.environmental.AM2315Full;

public class AM2315Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x5C").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var sensor = new AM2315Full(connection);

            long[] info = sensor.readDeviceInfo();
            checkTrue("device info model", info[0] != 0xFFFF);

            double[] v = sensor.read();
            checkTrue("temperature range", v[0] >= -40 && v[0] <= 125);
            checkTrue("humidity range", v[1] >= 0 && v[1] <= 100);
            checkTrue("readHumidity range", sensor.readHumidity() <= 100);
            checkTrue("readTemperature range", sensor.readTemperature() >= -40);
            int[] raw = sensor.readRaw();
            checkTrue("raw ranges", raw[0] <= 1000 && (raw[1] & 0x7FFF) <= 1250);
            double[] fresh = sensor.readFresh();
            checkTrue("readFresh range", fresh[0] >= -40 && fresh[1] <= 100);
            checkTrue("readRegisters length", sensor.readRegisters(0x00, 4).length == 4);

            sensor.writeUserRegisters(0x1234, 0xABCD);
            int[] user = sensor.readUserRegisters();
            checkTrue("user registers round trip", user[0] == 0x1234 && user[1] == 0xABCD);
            sensor.writeUserRegisters(0, 0);
            sensor.wake();
        }

        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
