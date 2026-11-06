///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.3.3
//DEPS it.uhde:periph-java:1.3.3

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.display.PCF8579Minimal;
import it.uhde.periph.chips.display.PCF8579Full;

public class PCF8579Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x3C"));

        try (var connection = new I2CConnection(bus, addr)) {

            // Minimal: construct (clears RAM, display on), pixels, columns.
            var lcd = new PCF8579Minimal(connection, 0, 16);
            try {
                lcd.clear();
                lcd.setPixel(0, 0, true);
                lcd.setPixel(39, 15, true);
                lcd.setPixel(45, 30, true); // out of range, ignored
                lcd.writeColumns(2, 1, new byte[]{(byte) 0xFF, (byte) 0x81, (byte) 0xFF});
                checkTrue("minimal_draw", true);
            } catch (Exception e) {
                System.out.println("FAIL minimal_draw: " + e);
                failed++;
            }

            // Full: exercise every method; readRam verifies the bytes really landed.
            var full = new PCF8579Full(connection, 0, 16);
            try {
                full.writeRam(16, 0, new byte[]{(byte) 0xAA, 0x55, 0x0F}, PCF8579Full.ACCESS_CHARACTER);
                byte[] got = full.readRam(16, 0, 3, PCF8579Full.ACCESS_CHARACTER);
                checkTrue("ram_roundtrip", got.length == 3 && got[0] == (byte) 0xAA && got[1] == 0x55 && got[2] == 0x0F);
            } catch (Exception e) {
                System.out.println("FAIL ram_roundtrip: " + e);
                failed++;
            }

            try {
                full.fill(true);
                full.fill(false);
                full.drawLine(0, 0, 39, 15, true);
                full.drawRect(0, 0, 40, 16, true, false);
                full.drawRect(4, 4, 8, 8, true, true);
                full.showBitmap(0, 0, new byte[]{0x18, 0x24, 0x42, (byte) 0x81});
                checkTrue("graphics", full.getPixel(0, 0));
            } catch (Exception e) {
                System.out.println("FAIL graphics: " + e);
                failed++;
            }

            try {
                full.setDisplay(PCF8579Full.DISPLAY_INVERSE);
                full.setDisplay(PCF8579Full.DISPLAY_ALL_ON);
                full.setDisplay(PCF8579Full.DISPLAY_NORMAL);
                full.setStartBank(1);
                full.scroll(1);
                full.scroll(-2);
                full.writeRamCascade(new byte[160], PCF8579Full.ACCESS_FULL_GRAPHIC);
                full.writeRam(16, 0, new byte[8], PCF8579Full.ACCESS_HALF_GRAPHIC);
                full.writeRam(16, 0, new byte[8], PCF8579Full.ACCESS_FULL_GRAPHIC);
                full.setMode(24, false);
                full.setMode(16, false);
                full.reset();
                checkTrue("configuration", true);
            } catch (Exception e) {
                System.out.println("FAIL configuration: " + e);
                failed++;
            }

            System.out.printf("===DONE: %d passed, %d failed===%n", passed, failed);
        }
        System.exit(failed == 0 ? 0 : 1);
    }
}
