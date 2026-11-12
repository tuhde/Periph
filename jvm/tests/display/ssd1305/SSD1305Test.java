///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 22+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS it.uhde:periph-connection:1.4.0
//DEPS it.uhde:periph-java:1.4.0

import it.uhde.periph.connection.I2CConnection;
import it.uhde.periph.chips.display.SSD1305Full;

public class SSD1305Test {

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
            var chip = new SSD1305Full(conn);
            checkTrue("geometry_128x64", chip.width() == 128 && chip.height() == 64);

            int s = chip.readStatus();
            checkTrue("status_not_busy", (s & 0x80) == 0);
            checkTrue("status_display_on", (s & 0x40) == 0);

            chip.sleep();
            checkTrue("sleep_sets_off_bit", (chip.readStatus() & 0x40) != 0);
            chip.wake();
            checkTrue("wake_clears_off_bit", (chip.readStatus() & 0x40) == 0);

            // RAM read-back: draw, show, read pages 0 and 3 back and compare with the framebuffer.
            chip.fill(false);
            chip.drawLine(0, 0, 31, 7, true);
            chip.drawLine(0, 31, 31, 24, true);
            chip.drawRect(40, 4, 20, 20, true, false);
            chip.show();
            boolean match0 = true;
            boolean match3 = true;
            for (int part = 0; part < 4; part++) {
                byte[] ram = chip.readPage(0, 2 + part * 16, 16);
                for (int x = 0; x < 16; x++) {
                    int expected = 0;
                    for (int b = 0; b < 8; b++) if (chip.getPixel(part * 16 + x, b)) expected |= 1 << b;
                    if ((ram[x] & 0xFF) != expected) match0 = false;
                }
                ram = chip.readPage(3, 2 + part * 16, 16);
                for (int x = 0; x < 16; x++) {
                    int expected = 0;
                    for (int b = 0; b < 8; b++) if (chip.getPixel(part * 16 + x, 24 + b)) expected |= 1 << b;
                    if ((ram[x] & 0xFF) != expected) match3 = false;
                }
            }
            checkTrue("ram_readback_page0", match0);
            checkTrue("ram_readback_page3", match3);

            chip.setContrast(0x40);
            chip.setContrast(0x80);
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
            chip.setClock(1, 7);
            chip.setPrecharge(2, 2);
            chip.setComConfig(true);
            chip.setVcomDeselect(0x35);
            chip.setPumpVoltage(0);
            chip.showPage(0);
            chip.show();
            checkTrue("commands_accepted", (chip.readStatus() & 0x80) == 0);
        } catch (Exception e) {
            System.out.println("FAIL exception: " + e);
            failed++;
        }
        System.out.println("===DONE: " + passed + " passed, " + failed + " failed===");
        System.exit(failed == 0 ? 0 : 1);
    }
}
