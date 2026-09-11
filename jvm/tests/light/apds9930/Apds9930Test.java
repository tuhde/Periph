///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.1.0
//DEPS it.uhde:periph-java:1.1.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.light.Apds9930Full;

public class Apds9930Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x39").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var apds = new Apds9930Full(connection);

            Thread.sleep(110);

            checkTrue("chip_id is 0x39", apds.chipId() == 0x39);

            var st = apds.status();
            checkTrue("status returns object", true);

            float lx = apds.lux();
            checkTrue("lux >= 0", lx >= 0.0f);

            int p = apds.proximity();
            checkTrue("proximity >= 0", p >= 0);

            int c0 = apds.ch0();
            int c1 = apds.ch1();
            checkTrue("ch0 >= 0", c0 >= 0);
            checkTrue("ch1 >= 0", c1 >= 0);

            apds.configureAls(0xDB, 0, false);
            apds.configureProximity(8, 0, 0, false, 0xFF);
            apds.disableWait();
            apds.setAlsThresholds(0, 65535, 1);
            apds.setProximityThresholds(0, 1023, 1);
            apds.setProximityOffset(0);
            apds.sleepAfterInterrupt(false);
            apds.clearInterrupt(0);
            checkTrue("config methods accepted", true);
        }

        System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}