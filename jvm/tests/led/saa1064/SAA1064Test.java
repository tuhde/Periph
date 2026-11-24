///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.3
//DEPS it.uhde:periph-java:1.5.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.led.SAA1064Minimal;
import it.uhde.periph.chips.led.SAA1064Full;
import java.util.Map;

public class SAA1064Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    static boolean throwsIllegal(Runnable r) {
        try { r.run(); } catch (IllegalArgumentException e) { return true; }
        return false;
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x38"));

        try (var connection = new I2CConnection(bus, addr)) {
            var display = new SAA1064Minimal(connection);
            checkTrue("constants", SAA1064Minimal.DIGITS == 4);
            display.showDigits(new int[]{1, 2, 3, 4});
            checkTrue("show_digits", true);
            display.showNumber(1234);
            checkTrue("show_number", true);
            checkTrue("show_number_range", throwsIllegal(() -> { try { display.showNumber(10000); } catch (java.io.IOException e) { throw new RuntimeException(e); } }));
            display.writeRaw(new int[]{0x7F, 0x7F}, 3);
            checkTrue("write_raw", true);
            display.clear();
            checkTrue("clear", true);

            var full = new SAA1064Full(connection);
            full.setMode(false);
            full.setMode(true);
            checkTrue("set_mode", true);
            full.blank(true, false);
            full.blank(false, true);
            full.blank(false, false);
            checkTrue("blank", true);
            full.setCurrent(3);
            full.setCurrent(21);
            full.setCurrent(12);
            checkTrue("set_current", true);
            full.segmentTest(true);
            full.segmentTest(false);
            checkTrue("segment_test", true);
            full.setSegmentMap(Map.of("a", 0, "b", 1, "c", 2, "d", 3, "e", 4, "f", 5, "g", 6, "dp", 7));
            checkTrue("set_segment_map", true);
            full.showDigit(1, 5, true);
            checkTrue("show_digit", true);
            full.setDecimalPoint(1, false);
            full.setDecimalPoint(4, true);
            checkTrue("set_decimal_point", true);
            full.readStatus();
            checkTrue("read_status", true);
            checkTrue("power_reset_flag_cleared", !full.powerResetFlag());
            full.writeControl(0x47);
            checkTrue("write_control", true);
            full.clear();
        }
        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
