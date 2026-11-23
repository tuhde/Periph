///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.5.2
//DEPS it.uhde:periph-java:1.5.2

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.led.HT16K33Minimal;
import it.uhde.periph.chips.led.HT16K33Full;

public class HT16K33Test {

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
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x70"));

        try (var connection = new I2CConnection(bus, addr)) {
            var matrix = new HT16K33Minimal(connection);
            checkTrue("constants", HT16K33Minimal.COMMONS == 8 && HT16K33Minimal.ROWS == 16);
            matrix.setPixel(0, 0);
            matrix.setPixel(7, 15, true);
            matrix.show();
            checkTrue("set_pixel_show", true);
            checkTrue("set_pixel_range", throwsIllegal(() -> matrix.setPixel(8, 0)));
            matrix.writeRaw(new byte[]{(byte) 0xFF, 0x00}, 0);
            checkTrue("write_raw", true);
            matrix.setBrightness(0);
            matrix.setBrightness(15);
            checkTrue("set_brightness", true);
            matrix.clear();
            checkTrue("clear", true);

            var full = new HT16K33Full(connection);
            full.setPixel(3, 4);
            full.show();
            checkTrue("get_pixel", full.getPixel(3, 4) && !full.getPixel(3, 5));
            for (double f : new double[]{2.0, 1.0, 0.5, 0.0}) full.setBlink(f);
            checkTrue("set_blink", true);
            full.displayOff();
            full.displayOn();
            checkTrue("display_off_on", true);
            full.standby();
            full.wake();
            checkTrue("standby_wake", true);
            full.setIntOutput(true, false);
            full.setIntOutput(true, true);
            full.setIntOutput(false, false);
            checkTrue("set_int_output", true);
            full.readKeys();
            checkTrue("read_keys", true);
            full.keyPressed();
            checkTrue("key_pressed", true);
            full.clear();
        }
        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
