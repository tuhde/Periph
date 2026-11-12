///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.display.SSD1306Full;
import it.uhde.periph.chips.display.SSD1306Minimal;

public class SSD1306Test {

    static int passed = 0;
    static int failed = 0;

    static void checkTrue(String label, boolean condition) {
        if (condition) { System.out.println("PASS " + label); passed++; }
        else           { System.out.println("FAIL " + label); failed++; }
    }

    public static void main(String[] args) throws Exception {
        int bus  = Integer.parseInt(System.getenv().getOrDefault("I2C_BUS",  "1"));
        int addr = Integer.decode(System.getenv().getOrDefault("I2C_ADDR", "0x3C"));

        try (var conn = new I2CConnection(bus, addr)) {
            var chip = new SSD1306Full(conn);
            checkTrue("geometry_128x64", chip.width() == 128 && chip.height() == 64);

            // The SSD1306 cannot be read over its serial interfaces: the picture is checked by eye.
            chip.fill(false);
            chip.drawLine(0, 0, 31, 7, true);
            chip.drawLine(0, 31, 31, 24, true);
            chip.drawRect(40, 4, 20, 20, true, false);
            chip.show();
            checkTrue("framebuffer_pixels", chip.getPixel(0, 0) && chip.getPixel(40, 4));

            chip.setContrast(0x40);
            chip.setContrast(0x7F);
            chip.setInvert(true);
            chip.setInvert(false);
            chip.setAllOn(true);
            chip.setAllOn(false);
            chip.setFlip(true, true);
            chip.setFlip(false, false);
            chip.setStartLine(16);
            chip.setStartLine(0);
            chip.setDisplayOffset(0);
            chip.setMultiplex(64);
            chip.setClock(1, 8);
            chip.setPrecharge(2, 2);
            chip.setComConfig(true, false);
            chip.setVcomh(0x20);
            chip.setChargePump(true);
            chip.setScrollHorizontal(true, 0, 7, 7);
            chip.scrollStart();
            boolean refused = false;
            try {
                chip.show();
            } catch (IllegalStateException e) {
                refused = true;
            }
            checkTrue("show_refused_while_scrolling", refused);
            chip.scrollStop();
            chip.setAddressingMode(SSD1306Minimal.ADDR_HORIZONTAL);
            chip.show();
            chip.setAddressingMode(SSD1306Minimal.ADDR_PAGE);
            chip.showPage(0);
            chip.show();
            checkTrue("commands_accepted", true);
        } catch (Exception e) {
            System.out.println("FAIL exception: " + e);
            failed++;
        }
        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
