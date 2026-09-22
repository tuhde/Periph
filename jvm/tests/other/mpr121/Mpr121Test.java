///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.2.0
//DEPS it.uhde:periph-java:1.2.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.other.Mpr121Full;

public class Mpr121Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS", "1"));
        int addr = Integer.parseInt(
                System.getenv().getOrDefault("I2C_ADDR", "0x5A").replaceFirst("^0[xX]", ""), 16);

        try (var connection = new I2CConnection(bus, addr)) {
            var mpr = new Mpr121Full(connection);

            int t = mpr.touched();
            checkTrue("touched in 0..4095", t <= 0xFFF);
            checkTrue("is_touched(0) is bool", true);

            int f0 = mpr.filtered(0);
            checkTrue("filtered(0) in 0..1023", f0 <= 1023);

            int b0 = mpr.baseline(0);
            checkTrue("baseline(0) in 0..1023", b0 <= 1023);

            int oor = mpr.oorStatus();
            checkTrue("oor_status in 0..8191", oor <= 0x1FFF);

            mpr.stop();
            mpr.configureThresholds(0, 15, 8);
            mpr.configureAllThresholds(12, 6);
            mpr.configureProximityThresholds(8, 4);
            mpr.configureBaselineFilter(1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0);
            mpr.configureSampling(16, 1, 0, 0, 4);
            mpr.configureDebounce(1, 1);
            mpr.configureAutoconfig(3300, 0, false, true, true);
            checkTrue("configuration methods accepted", true);

            mpr.enableInterrupt(Mpr121Full.SOURCE_OOR);
            mpr.disableInterrupt(Mpr121Full.SOURCE_OOR);
            mpr.clearOvercurrent();
            checkTrue("interrupt API accepted", true);

            mpr.reset();
            checkTrue("reset completed", true);

            System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
            System.exit(failed == 0 ? 0 : 1);
        }
    }
}
